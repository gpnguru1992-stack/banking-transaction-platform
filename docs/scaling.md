# Load Balancing and Scaling

This page explains how traffic is spread across service instances, how the platform scales, why scaling is safe
for money movement, and what is not covered yet.

Short answer: in Kubernetes (`k8s/base`), load balancing and autoscaling are already configured.
The local `docker-compose.yml` runs one instance of each service.

## 1. Load balancing

```
Internet
   │  HTTPS (api.bank.example)
   ▼
Ingress (nginx, TLS)            k8s/base/ingress.yaml
   │
   ▼
Service: api-gateway            spreads requests over all gateway pods
   │
   ▼
Service: account-service, payment-service, ...   (ClusterIP, one per service)
   │
   ▼
pods of that service
```

- The **Ingress** is the public entry point. It ends TLS and sends `/api/**` traffic to the API Gateway.
- Every service has a Kubernetes **`Service`**. It balances requests across all healthy pods of that service.
  Example: account-service runs 3 pods, so the gateway's calls are shared between the 3.
- **Readiness probes** (`/actuator/health/readiness`) take a pod out of rotation until it is ready
  (database, Kafka and so on are reachable), so users never hit a pod that is starting up.
- **`preStop` sleep + graceful shutdown**: when a pod stops, it first leaves the load balancer, then finishes
  in-flight requests before exiting. Deployments do not drop requests.

## 2. Autoscaling

Each service has a `HorizontalPodAutoscaler` that adds pods when average CPU is above **70%** and removes them when load drops.

| Service | Min pods | Max pods |
|---|---|---|
| api-gateway | 3 | 10 |
| account-service | 3 | 10 |
| customer-service | 2 | 6 |
| payment-service | 2 | 6 |
| transaction-service | 2 | 6 |
| notification-service | 2 | 6 |

Each pod requests `250m` CPU / `512Mi` memory, with a `1Gi` memory limit. Requests are what the HPA percentage is measured against.

## 3. Staying available

- **Zone spreading** (`topologySpreadConstraints`): pods of one service are spread across availability zones,
  so losing one zone does not take a service down.
- **PodDisruptionBudget**: at least 1 pod of each service stays up during node upgrades or maintenance.
- **Rolling updates** with `maxUnavailable: 0`: a new pod must be ready before an old one is removed.
- **Startup / liveness probes**: slow-starting JVMs get up to 3 minutes to start; stuck pods are restarted.

## 4. Why running many copies is safe for money

Adding pods must never cause double debits or overdrafts. That holds because:

| Concern | How it is handled |
|---|---|
| Any pod can handle any request | Services are stateless; all state is in PostgreSQL, Redis or Kafka. |
| Same transfer sent twice, hitting two different pods | Shared Redis lock on the `Idempotency-Key`, plus a database `UNIQUE (customer_id, idempotency_key)` as the final guard. |
| Two pods debiting the same account at the same time | `SELECT ... FOR UPDATE` row locks taken in a fixed account order (no deadlocks) and `CHECK (balance >= 0)`. `TransferServiceIT` runs 16 concurrent transfers to prove there is no overdraft. |
| Rate limit per user across pods | The gateway rate limiter stores its counters in Redis, so the limit is shared by all gateway pods. |
| Several pods publishing the outbox | `FOR UPDATE SKIP LOCKED`: each pod takes different rows, so no event is sent twice by the relay. |
| Several consumer pods reading Kafka | Pods of one service share a consumer group. Kafka gives each pod its own partitions. Example: 6 partitions and 3 transaction-service pods means 2 partitions each. |
| An event delivered twice by Kafka | Each consumer records `(event_id, consumer)` in `processed_message` in the same transaction as its work, so duplicates are skipped. |
| Order of events for one account | Events are keyed by account number, so all events for one account go to the same partition, in order. |

## 5. Scaling locally (Docker Compose)

Compose runs one instance of each service. It is meant for development and the end-to-end demo, not for load tests.

## 6. Current limits and gaps

| Gap | Why it matters | Suggested fix |
|---|---|---|
| Kafka topics have 6 partitions | At most 6 pods per consumer service do useful work; more pods sit idle. | Create topics with more partitions (for example 24) before going live. Partitions can be increased later, but that changes which partition a key maps to. |
| HPA scales on CPU only | A notification or statement backlog (Kafka consumer lag) does not add pods. | KEDA with a Kafka scaler, scaling consumers on lag. |
| Ingress assumes nginx | On AWS there is no ALB configuration yet. | AWS Load Balancer Controller Ingress (ALB, ACM certificate, WAF). |
| Database connections | Every service at its maximum pods, times the Hikari pool size, can exceed PostgreSQL `max_connections`. | Cap pool sizes per pod, and use PgBouncer or Amazon RDS Proxy. |
| Single primary database per service | Writes do not scale out; heavy read endpoints (statements, audit) share the primary. | Read replicas for read-only queries; partition large tables (ledger, audit) by month. |
| Not tested on a real cluster | The Kubernetes manifests are checked for syntax only. | Deploy to a test EKS cluster and run a load test (for example k6 or Gatling). |
