# Lakehouse: Iceberg tables queried with Dremio

`analytics-service` copies every money event from Kafka into **Apache Iceberg** tables. Analysts and
reports can then query banking data with SQL in **Dremio**, without touching the production databases.

```
account-events ─┐
                ├─▶ analytics-service ──▶ Iceberg tables (Parquet files in S3) ◀── Dremio (SQL, BI tools)
payment-events ─┘     micro-batches            catalog: Nessie
```

| Piece | Local (docker compose) | Production (AWS) |
|---|---|---|
| Object store | SeaweedFS (S3 API) | Amazon S3 |
| Iceberg catalog | Nessie | Nessie, AWS Glue or an Iceberg REST catalog (config only, see below) |
| SQL engine | Dremio OSS | Dremio, Athena, Trino or Spark |

## Tables

### `banking.transactions`: one row per money event

| Column | Example |
|---|---|
| `reference_id` | transfer / deposit id |
| `transaction_type` | `TRANSFER`, `INTERBANK`, `DEPOSIT` |
| `status` | `COMPLETED`, `FAILED`, `PENDING`, `SETTLED`, `REFUNDED` |
| `rail` | `IMPS`, `NEFT`, `RTGS` (interbank only) |
| `customer_id`, `from_account`, `to_account`, `counterparty_ifsc` | `CUST1001`, `ACC1001`, `123456789012`, `HDFC0001234` |
| `amount` (decimal 19,2), `currency` | `10000.00`, `INR` |
| `utr`, `failure_code` | `HDFCI140177598203`, `BENEFICIARY_ACCOUNT_INVALID` |
| `correlation_id` | same ID as in logs, traces and the audit table |
| `occurred_at`, `business_date` | UTC time, and date in India (IST) |

An interbank transfer has two rows: `PENDING`, then `SETTLED` or `REFUNDED`. Its current status is the latest row
for its `reference_id` (see the query below).

### `banking.events`: every event, raw

`event_id`, `event_type`, `aggregate_type`, `aggregate_id`, `producer`, `correlation_id`, `topic`,
`kafka_partition`, `kafka_offset`, `occurred_at`, `payload` (JSON). Use it for audit, debugging and new reports.

Both tables are partitioned by day of `occurred_at`, stored as Parquet (zstd), Iceberg format v2.

**Not copied:** `customer-events`. It contains personal data (email, phone) that should not go into the analytics lake.

## No lost rows, no duplicate rows

- The service collects events and writes a batch every 5–10 seconds (or every 1,000 rows).
- Each batch is **one Iceberg commit**. The commit also records the last Kafka offset of each partition in the
  snapshot summary (`banking.kafka-offset.analytics-service.<topic>-<partition>`).
- On startup or rebalance, the service reads those offsets from Iceberg and continues from the next offset. It
  ignores Kafka's own committed offsets.
- Example: the service writes offsets 0–1, buffers offset 2, then crashes. On restart Iceberg says "last = 1", so it
  re-reads from 2. Offset 2 is written once, and 0–1 are not written again.
- A malformed message is skipped and counted (`banking_lakehouse_events_malformed_total`). It never blocks the stream.

Metrics: `banking_lakehouse_rows_written_total`, `banking_lakehouse_commit_latency_seconds`, Kafka consumer lag.

## Run it locally

```bash
mvn -DskipTests package
docker compose --profile lakehouse up -d --build   # adds SeaweedFS, Nessie, analytics-service, Dremio
./scripts/e2e-transfer.sh && ./scripts/e2e-interbank.sh   # create some transactions
./scripts/dremio-setup.sh                          # connects Dremio to the lakehouse and runs sample queries
```

Dremio needs about 3 GB of memory. Open http://localhost:9047 (user `admin`, password `dremio-demo-pass1`, local only),
then **Sources → lakehouse → banking**.

### Sample queries

```sql
-- Volume by type and status
SELECT transaction_type, status, COUNT(*) AS txns, SUM(amount) AS total_inr
FROM lakehouse.banking.transactions
GROUP BY transaction_type, status;

-- Interbank transfers: latest status per transfer, by rail
SELECT rail, status, COUNT(*) AS transfers, SUM(amount) AS total_inr
FROM (
  SELECT rail, status, amount,
         ROW_NUMBER() OVER (PARTITION BY reference_id ORDER BY occurred_at DESC) AS rn
  FROM lakehouse.banking.transactions
  WHERE transaction_type = 'INTERBANK'
) t
WHERE rn = 1
GROUP BY rail, status;

-- Daily outflow per customer (IST business date)
SELECT business_date, customer_id, SUM(amount) AS sent_inr
FROM lakehouse.banking.transactions
WHERE status IN ('COMPLETED', 'SETTLED') AND transaction_type IN ('TRANSFER', 'INTERBANK')
GROUP BY business_date, customer_id
ORDER BY business_date DESC;

-- Time travel: the table as it was an hour ago
SELECT COUNT(*) FROM lakehouse.banking.transactions AT TIMESTAMP DATE_SUB(NOW(), INTERVAL '1' HOUR);
```

## Production configuration

Everything under `banking.lakehouse.catalog.*` goes unchanged to Iceberg's `CatalogUtil`, so the catalog can be
changed without changing code. For example, for AWS Glue + S3:

```yaml
banking.lakehouse.catalog:
  catalog-impl: org.apache.iceberg.aws.glue.GlueCatalog
  warehouse: s3://bank-lakehouse-prod/
  io-impl: org.apache.iceberg.aws.s3.S3FileIO
```

Credentials come from the pod's IAM role (IRSA); there are no keys in config. Run 1 replica per few partitions;
replicas split the Kafka partitions and can commit to the same table safely (Iceberg retries on conflict).

## Not covered yet

- Compaction of small files and expiry of old snapshots. Schedule Iceberg `rewrite_data_files` /
  `expire_snapshots` (Spark or Dremio `OPTIMIZE` / `VACUUM`).
- Row-level access rules in Dremio (for example, masking account numbers for some analysts).
- The `events` and `transactions` tables commit separately. Each is exactly-once on its own, but for a few
  seconds one table can be ahead of the other.
