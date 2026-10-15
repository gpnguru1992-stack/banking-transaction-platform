#!/usr/bin/env bash
# End-to-end check of the transfer flow against the docker-compose stack.
# Asha (CUST1001) sends INR 10,000 from ACC1001 to Ravi's ACC2001 through the API gateway.
set -euo pipefail

GATEWAY=${GATEWAY:-http://localhost:8080}
KEYCLOAK=${KEYCLOAK:-http://localhost:8180}

token() {
  curl -sf -X POST "$KEYCLOAK/realms/banking/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=banking-app -d username="$1" -d password="$2" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
}
balance() {
  curl -sf -H "Authorization: Bearer $1" "$GATEWAY/api/v1/accounts/$2/balance" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["balance"])'
}

ASHA=$(token asha asha-demo-pass)
RAVI=$(token ravi ravi-demo-pass)
OPS=$(token ops ops-demo-pass)
KEY=$(python3 -c 'import uuid; print(uuid.uuid4())')
BODY='{"fromAccount":"ACC1001","toAccount":"ACC2001","amount":10000,"currency":"INR","reference":"Rent October"}'

echo "Before: ACC1001=$(balance "$ASHA" ACC1001) ACC2001=$(balance "$RAVI" ACC2001)"

echo "--- POST /api/v1/transfers (Idempotency-Key: $KEY)"
curl -s -D - -o /tmp/transfer.json -H "Authorization: Bearer $ASHA" -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' -d "$BODY" "$GATEWAY/api/v1/transfers" | grep -iE '^HTTP|correlation|replayed|location'
cat /tmp/transfer.json; echo
TRANSFER_ID=$(python3 -c 'import json; print(json.load(open("/tmp/transfer.json"))["transferId"])')

echo "--- Same request again (client retry): must replay, not move money twice"
curl -s -D - -o /dev/null -H "Authorization: Bearer $ASHA" -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' -d "$BODY" "$GATEWAY/api/v1/transfers" | grep -iE '^HTTP|replayed'

echo "--- Same key, different amount: must be rejected"
curl -s -H "Authorization: Bearer $ASHA" -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' \
  -d "${BODY/10000/9999}" "$GATEWAY/api/v1/transfers"; echo

echo "--- Insufficient funds: rejected and recorded"
curl -s -H "Authorization: Bearer $ASHA" -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" \
  -H 'Content-Type: application/json' -d "${BODY/10000/199999}" "$GATEWAY/api/v1/transfers"; echo

echo "After: ACC1001=$(balance "$ASHA" ACC1001) ACC2001=$(balance "$RAVI" ACC2001)"

echo "--- Waiting for downstream consumers"
sleep 5
echo "Payment:"; curl -s -H "Authorization: Bearer $ASHA" "$GATEWAY/api/v1/payments/$TRANSFER_ID"; echo
echo "Asha statement:"; curl -s -H "Authorization: Bearer $ASHA" "$GATEWAY/api/v1/accounts/ACC1001/transactions?size=3"; echo
echo "Ravi statement:"; curl -s -H "Authorization: Bearer $RAVI" "$GATEWAY/api/v1/accounts/ACC2001/transactions?size=3"; echo
echo "Asha notifications:"; curl -s -H "Authorization: Bearer $ASHA" "$GATEWAY/api/v1/notifications?size=4"; echo
echo "Audit trail for transfer (ops):"; curl -s -H "Authorization: Bearer $OPS" "$GATEWAY/api/v1/audit?aggregateId=$TRANSFER_ID"; echo
echo "Emails captured by Mailpit: http://localhost:8025"
