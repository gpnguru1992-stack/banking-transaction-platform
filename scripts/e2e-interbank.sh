#!/usr/bin/env bash
# End-to-end check of interbank transfers (IMPS / NEFT / RTGS) against the docker-compose stack.
# The clearing-simulator plays the other bank: account ending 0000 = rejected, ending 9999 = slow first reply.
set -euo pipefail

GATEWAY=${GATEWAY:-http://localhost:8080}
KEYCLOAK=${KEYCLOAK:-http://localhost:8180}

token() {
  curl -sf -X POST "$KEYCLOAK/realms/banking/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=banking-app -d username="$1" -d password="$2" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
}
balance() {
  curl -sf -H "Authorization: Bearer $ASHA" "$GATEWAY/api/v1/accounts/ACC1001/balance" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["balance"])'
}
# send RAIL IFSC ACCOUNT AMOUNT -> prints transfer id
send() {
  local body key
  key=$(python3 -c 'import uuid; print(uuid.uuid4())')
  body=$(printf '{"fromAccount":"ACC1001","beneficiaryName":"Meena Iyer","beneficiaryAccount":"%s","beneficiaryIfsc":"%s","rail":"%s","amount":%s,"currency":"INR","reference":"e2e %s"}' "$3" "$2" "$1" "$4" "$1")
  curl -s -o /tmp/ibt.json -w '%{http_code}' -H "Authorization: Bearer $ASHA" -H "Idempotency-Key: $key" \
    -H 'Content-Type: application/json' -d "$body" "$GATEWAY/api/v1/interbank-transfers" > /tmp/ibt.code
  echo "    HTTP $(cat /tmp/ibt.code): $(python3 -c 'import json; d=json.load(open("/tmp/ibt.json")); print(d.get("errorCode") or d.get("status"))')" >&2
  python3 -c 'import json; print(json.load(open("/tmp/ibt.json")).get("transferId",""))'
}
# wait_for ID STATUS TIMEOUT_SECONDS
wait_for() {
  for _ in $(seq 1 "$3"); do
    status=$(curl -sf -H "Authorization: Bearer $ASHA" "$GATEWAY/api/v1/interbank-transfers/$1" \
      | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["status"], d.get("utr") or d.get("failureCode") or "")')
    if [[ $status == $2* ]]; then echo "    -> $status"; return 0; fi
    sleep 1
  done
  echo "    !! expected $2, still: $status"; exit 1
}
clearing() {
  curl -sf -H "Authorization: Bearer $ASHA" "$GATEWAY/api/v1/payments/interbank/$1" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print("    clearing:", d["status"], "attempts:", d["attempts"])'
}

ASHA=$(token asha asha-demo-pass)
START=$(balance)
echo "Asha ACC1001 balance: $START"

echo "1) IMPS 10,000 to HDFC0001234 / 123456789012 (accepted)"
ID1=$(send IMPS HDFC0001234 123456789012 10000); wait_for "$ID1" SETTLED 30; clearing "$ID1"

echo "2) IMPS 5,000 to ICIC0000001 / 123456780000 (beneficiary bank rejects -> refund)"
ID2=$(send IMPS ICIC0000001 123456780000 5000); wait_for "$ID2" REFUNDED 30; clearing "$ID2"

echo "3) IMPS 2,000 to SBIN0000456 / 123456789999 (first reply times out -> status inquiry -> settled, sent once)"
ID3=$(send IMPS SBIN0000456 123456789999 2000); wait_for "$ID3" SETTLED 60; clearing "$ID3"

echo "4) NEFT 1,000 to AXIS0000789 / 223344556677 (waits for the next NEFT batch)"
ID4=$(send NEFT AXIS0000789 223344556677 1000); wait_for "$ID4" SETTLED 90; clearing "$ID4"

echo "5) RTGS 5,000 (below the RTGS minimum -> rejected up front, no money moves)"
send RTGS HDFC0001234 123456789012 5000 > /dev/null

END=$(balance)
echo "Asha ACC1001 balance: $START -> $END (expected -13000: 10,000 + 2,000 + 1,000 settled, 5,000 refunded)"
python3 -c "import sys; sys.exit(0 if abs(($START - $END) - 13000) < 0.01 else 1)" || { echo '!! balance mismatch'; exit 1; }

sleep 3
echo "--- Statement (latest 6 lines)"
curl -sf -H "Authorization: Bearer $ASHA" "$GATEWAY/api/v1/accounts/ACC1001/transactions?size=6" \
  | python3 -c 'import json,sys; [print("   ", l["direction"], l["amount"], l["type"], l["description"][:80]) for l in json.load(sys.stdin)["content"]]'
echo "--- Notifications (latest 4 email subjects)"
curl -sf -H "Authorization: Bearer $ASHA" "$GATEWAY/api/v1/notifications?size=8" \
  | python3 -c 'import json,sys; [print("   ", n["subject"]) for n in json.load(sys.stdin)["content"] if n["channel"]=="EMAIL"][:4]'
echo "Interbank e2e passed"
