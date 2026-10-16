# Banking Transaction Platform

A real-time banking transaction platform built as Spring Boot 3 (Java 17) microservices that talk over REST and Apache Kafka.
It is built with the patterns a real bank needs for moving money: idempotent APIs, row-level locking,
a double-entry ledger, a transactional outbox, idempotent consumers, dead-letter topics, JWT security at every hop,
and full observability.

```
Customer ──REST──▶ API Gateway ──▶ Account Service ──REST──▶ Customer Service
                   (JWT, rate limit,      │  (debit + credit in one DB transaction,
                    correlation id)       │   ledger, outbox)
                                          ▼
                                 Kafka: account-events
                       ┌──────────────────┼──────────────────┐
                       ▼                  ▼                  ▼
               Payment Service   Notification Service   Transaction Service
                (payments DB)       (email / SMS)      (history + audit DB)
```

## Services

| Module | Port | Owns | Database |
|---|---|---|---|
| `api-gateway` | 8080 | Routing, JWT validation, per-user Redis rate limit, `X-Correlation-Id`, blocks `/internal/**` | – |
| `customer-service` | 8081 | Customer profile, status, KYC, contact preferences; publishes `customer-events` | `customer_db` |
| `account-service` | 8082 | Accounts, balances, debit/credit, **transfers**, ledger; publishes `account-events` | `account_db` |
| `payment-service` | 8083 | Payment records and daily settlement totals (consumes `account-events`) | `payment_db` |
| `transaction-service` | 8084 | Account statements and the platform-wide audit trail (consumes all events) | `transaction_db` |
| `notification-service` | 8085 | Email/SMS alerts with retry and opt-out (consumes `account-events`, `customer-events`) | `notification_db` |
| `banking-common` | – | Event envelope, outbox relay, inbox, Kafka retry/DLT, security, errors, correlation, JSON logging | – |

Each service is its own Maven module, Docker image, Kubernetes Deployment and database. One repository keeps the
whole system easy to clone, build and run together.

## What happens on `POST /api/v1/transfers`

```http
POST /api/v1/transfers
Authorization: Bearer <customer JWT>
Idempotency-Key: 6f1c...            (required, unique per business intent)

{"fromAccount":"ACC1001","toAccount":"ACC2001","amount":10000,"currency":"INR","reference":"Rent"}
```

1. **Authenticate**: the gateway and account-service both validate the JWT (issuer, signature, audience `banking-api`). The `customer_id` claim identifies the customer.
2. **Validate request**: bean validation (account format, amount > 0, max 2 decimals, ISO currency), same-account check, supported currency, per-transfer limit.
3. **Prevent duplicates**: if `(customer_id, Idempotency-Key)` already exists, the stored result is returned with `Idempotent-Replayed: true`; a different body with the same key returns `422 IDEMPOTENCY_KEY_REUSED`. A short Redis lock stops two identical in-flight requests; a database unique constraint is the final guard if Redis is down.
4. **Check the customer**: account-service calls customer-service (`ACTIVE` + KYC `VERIFIED`) with timeouts, retry and a circuit breaker. If it cannot confirm, the transfer is refused (fail closed).
5. **In one database transaction**:
   - lock both account rows (`SELECT ... FOR UPDATE`) in a fixed order so concurrent transfers cannot deadlock,
   - check ownership, account status, currency, balance and the daily limit,
   - debit the source, credit the destination,
   - write two immutable ledger lines (DEBIT + CREDIT),
   - save the transfer,
   - write a `TransferCompleted` event to the **outbox** table.
6. **Publish**: the outbox relay sends the event to Kafka `account-events` (key = source account, `acks=all`, idempotent producer). Money and event are never out of sync, even if Kafka is down at the time.
7. **Downstream** (each consumer is idempotent through its `processed_message` inbox; failures retry with backoff and then go to `account-events.DLT`):
   - payment-service records the payment,
   - transaction-service writes statement lines for both accounts and the audit record,
   - notification-service creates debit/credit alerts and delivers them by email and SMS with retries.
8. **Business failures** (insufficient funds, frozen account, limit exceeded) are stored as `FAILED` transfers, publish `TransferFailed` (audited, customer notified) and return an RFC 7807 problem response.

Every request carries an `X-Correlation-Id` that flows through HTTP, Kafka headers, logs, traces and the audit table,
so one ID finds everything that happened for a transfer.

## Run it locally

Requirements: Java 17, Maven 3.9, Docker.

```bash
mvn -DskipTests package           # build all services
docker compose up -d --build      # Postgres, Kafka, Redis, Keycloak, Mailpit, observability + 6 services
./scripts/e2e-transfer.sh         # Asha sends INR 10,000 from ACC1001 to Ravi's ACC2001
```

Demo data (only loaded by docker-compose via `SPRING_FLYWAY_LOCATIONS=...,classpath:db/seed`):

| Login (Keycloak) | Role | Customer | Account | Opening balance |
|---|---|---|---|---|
| `asha` / `asha-demo-pass` | CUSTOMER | CUST1001 | ACC1001 | INR 100,000.00 |
| `ravi` / `ravi-demo-pass` | CUSTOMER | CUST1002 | ACC2001 | INR 25,000.00 |
| `ops` / `ops-demo-pass` | OPERATIONS | – | – | – |
| `admin` / `admin-demo-pass` | ADMIN | – | – | – |

Get a token by hand:

```bash
TOKEN=$(curl -s -X POST http://localhost:8180/realms/banking/protocol/openid-connect/token \
  -d grant_type=password -d client_id=banking-app -d username=asha -d password=asha-demo-pass | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/accounts
```

| Tool | URL |
|---|---|
| API gateway | http://localhost:8080 |
| Swagger UI (per service) | http://localhost:8082/swagger-ui.html (8081–8085) |
| Keycloak | http://localhost:8180 (admin / admin) |
| Mailpit (sent emails) | http://localhost:8025 |
| Grafana dashboard "Banking Platform" | http://localhost:3000 |
| Prometheus (+ alert rules) | http://localhost:9090 |
| Jaeger traces | http://localhost:16686 |

## API overview (through the gateway)

| Method & path | Who | Service |
|---|---|---|
| `POST /api/v1/transfers` (header `Idempotency-Key`) | customer | account |
| `GET /api/v1/transfers/{id}` | owner / staff | account |
| `GET /api/v1/accounts`, `GET /api/v1/accounts/{no}`, `GET /api/v1/accounts/{no}/balance` | owner / staff | account |
| `POST /api/v1/accounts`, `POST /api/v1/accounts/{no}/deposits` | staff | account |
| `PUT /api/v1/accounts/{no}/status` | admin | account |
| `GET /api/v1/accounts/{no}/transactions` | owner / staff | transaction |
| `GET /api/v1/audit?aggregateId=&correlationId=` | staff | transaction |
| `GET /api/v1/payments`, `GET /api/v1/payments/{transferId}` | owner / staff | payment |
| `GET /api/v1/payments/settlement?date=` | staff | payment |
| `GET /api/v1/notifications` | customer | notification |
| `POST /api/v1/customers`, `PUT /api/v1/customers/{id}/kyc` | staff | customer |
| `GET /api/v1/customers/me`, `PATCH /api/v1/customers/me/contact` | customer | customer |
| `PUT /api/v1/customers/{id}/status` | admin | customer |

Errors are RFC 7807 `application/problem+json` with a stable `errorCode` and the `correlationId`; internal details are never returned.

## Reliability and data integrity

- **No lost or phantom events**: transactional outbox (`outbox_event`) + relay using `FOR UPDATE SKIP LOCKED`, so many replicas can relay safely.
- **Exactly-once effect for consumers**: `processed_message (event_id, consumer)` inbox written in the same transaction as the side effect.
- **Poison messages** go straight to `<topic>.DLT`; transient errors retry with exponential backoff first.
- **No overdrafts under concurrency**: pessimistic row locks + `CHECK (balance >= 0)`; tested with 16 parallel transfers.
- **Immutable history**: ledger and audit tables reject `UPDATE`/`DELETE` at the database level.
- **Optimistic versions** on customer and account rows protect admin/profile updates.
- **Graceful shutdown**, liveness/readiness probes and fail-fast HTTP timeouts.

## Security

- OAuth2 / OpenID Connect (Keycloak locally; any OIDC provider in production). Every service validates the JWT itself (zero trust); roles come from `realm_access.roles`.
- Customers can only see and move money from their own accounts; staff (`OPERATIONS`, `ADMIN`) endpoints use `@PreAuthorize`.
- `/internal/**` (service-to-service) is blocked at the gateway and restricted by Kubernetes `NetworkPolicy`.
- National IDs are stored masked; notifications mask account numbers (`XX1001`) and phone numbers in logs.
- Containers run as non-root with a read-only root filesystem in Kubernetes. No secrets in the repo: Kubernetes reads DB/SMTP credentials from `Secret`s (e.g. External Secrets + AWS Secrets Manager). The passwords in `docker-compose.yml` and the Keycloak realm are local demo values only.

## Observability

- **Metrics**: Micrometer → Prometheus (`/actuator/prometheus`): `banking_transfers_total{outcome,code}`, transfer latency, `outbox_events_pending`, `banking_notifications_total`, Kafka consumer lag, circuit-breaker state, HTTP/JVM/DB pool metrics. Alert rules in `deploy/prometheus/alerts.yml`.
- **Tracing**: OpenTelemetry (OTLP) → collector → Jaeger, across HTTP and Kafka.
- **Logging**: JSON logs (Logstash encoder) with `traceId`, `spanId`, `correlationId` and `service`, ready for ELK or Splunk. Use profile `local` for plain-text logs.

## Testing

```bash
mvn verify      # unit tests (JUnit 5, Mockito) + integration tests (*IT, Testcontainers PostgreSQL)
```

`TransferServiceIT` runs the real transfer path against PostgreSQL: money movement, ledger, outbox event,
idempotent replay, key reuse, insufficient funds, ownership, KYC refusal and concurrent transfers.

## Deploy

- `Dockerfile` builds a small non-root runtime image for any module: `docker build --build-arg MODULE=account-service .`
- `k8s/base` (Kustomize): Deployments, Services, HPAs, PodDisruptionBudgets, NetworkPolicies, Ingress, ConfigMap. Intended for EKS with Amazon RDS (PostgreSQL), MSK (Kafka) and ElastiCache (Redis).
- Load balancing, autoscaling and scaling limits: see [docs/scaling.md](docs/scaling.md).
- `.github/workflows/ci.yml`: build and test on every PR, validate manifests, build images, push to GHCR on `main`.

## Project layout

```
banking-common/        shared library (auto-configured)
api-gateway/           Spring Cloud Gateway
customer-service/      account-service/   payment-service/
transaction-service/   notification-service/
deploy/                Keycloak realm, Postgres init, Prometheus, Grafana, OTel collector config
k8s/base/              Kubernetes manifests
scripts/               end-to-end demo script
```
