#!/usr/bin/env bash
# Connects Dremio to the Iceberg lakehouse (Nessie catalog + S3) and runs sample queries.
# Run after: docker compose --profile lakehouse up -d
set -euo pipefail

DREMIO=${DREMIO:-http://localhost:9047}
DREMIO_USER=${DREMIO_USER:-admin}
DREMIO_PASSWORD=${DREMIO_PASSWORD:-dremio-demo-pass1}

echo "Waiting for Dremio at $DREMIO ..."
for _ in $(seq 1 90); do curl -sf -o /dev/null "$DREMIO/apiv2/server_status" && break; sleep 5; done

# First start only: create the admin user (ignored if it already exists).
curl -s -o /dev/null -X PUT "$DREMIO/apiv2/bootstrap/firstuser" -H 'Content-Type: application/json' \
  -d "{\"userName\":\"$DREMIO_USER\",\"firstName\":\"Bank\",\"lastName\":\"Admin\",\"email\":\"admin@bank.example\",\"createdAt\":$(date +%s)000,\"password\":\"$DREMIO_PASSWORD\"}" || true

TOKEN=$(curl -sf -X POST "$DREMIO/apiv2/login" -H 'Content-Type: application/json' \
  -d "{\"userName\":\"$DREMIO_USER\",\"password\":\"$DREMIO_PASSWORD\"}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
AUTH="Authorization: _dremio$TOKEN"

if curl -sf -o /dev/null -H "$AUTH" "$DREMIO/api/v3/catalog/by-path/lakehouse"; then
  echo "Source 'lakehouse' already exists"
else
  curl -sf -o /dev/null -X POST "$DREMIO/api/v3/catalog" -H "$AUTH" -H 'Content-Type: application/json' -d '{
    "entityType": "source", "type": "NESSIE", "name": "lakehouse",
    "config": {
      "nessieEndpoint": "http://nessie:19120/api/v2",
      "nessieAuthType": "NONE",
      "credentialType": "ACCESS_KEY",
      "awsAccessKey": "lakehouse",
      "awsAccessSecret": "lakehouse-demo-secret",
      "awsRootPath": "/warehouse",
      "secure": false,
      "propertyList": [
        {"name": "fs.s3a.path.style.access", "value": "true"},
        {"name": "fs.s3a.endpoint", "value": "seaweedfs:8333"},
        {"name": "dremio.s3.compat", "value": "true"}
      ]
    }
  }'
  echo "Created Dremio source 'lakehouse' (Nessie + S3)"
fi

# sql "<query>": runs it through the REST API and prints the rows
sql() {
  local job state
  job=$(curl -sf -X POST "$DREMIO/api/v3/sql" -H "$AUTH" -H 'Content-Type: application/json' \
    -d "$(python3 -c 'import json,sys; print(json.dumps({"sql": sys.argv[1]}))' "$1")" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
  for _ in $(seq 1 60); do
    state=$(curl -sf -H "$AUTH" "$DREMIO/api/v3/job/$job" | python3 -c 'import json,sys; print(json.load(sys.stdin)["jobState"])')
    case $state in COMPLETED) break ;; FAILED|CANCELED)
      curl -sf -H "$AUTH" "$DREMIO/api/v3/job/$job" | python3 -c 'import json,sys; print("   query failed:", json.load(sys.stdin).get("errorMessage"))'; return 1 ;; esac
    sleep 1
  done
  curl -sf -H "$AUTH" "$DREMIO/api/v3/job/$job/results?limit=50" \
    | python3 -c 'import json,sys; [print("   ", r) for r in json.load(sys.stdin)["rows"]]'
}

echo "--- Money movements by type and status"
sql 'SELECT transaction_type, status, COUNT(*) AS txns, SUM(amount) AS total_inr FROM lakehouse.banking.transactions GROUP BY transaction_type, status ORDER BY transaction_type, status'
echo "--- Interbank volume by rail (latest status per transfer)"
sql 'SELECT rail, status, COUNT(*) AS transfers, SUM(amount) AS total_inr FROM (SELECT rail, status, amount, ROW_NUMBER() OVER (PARTITION BY reference_id ORDER BY occurred_at DESC) AS rn FROM lakehouse.banking.transactions WHERE transaction_type = '"'"'INTERBANK'"'"') t WHERE rn = 1 GROUP BY rail, status ORDER BY rail, status'
echo "--- Events per type"
sql 'SELECT event_type, COUNT(*) AS events FROM lakehouse.banking.events GROUP BY event_type ORDER BY events DESC'
echo "Open Dremio: $DREMIO  (user $DREMIO_USER) -> Sources -> lakehouse -> banking"
