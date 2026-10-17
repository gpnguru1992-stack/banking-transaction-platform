# Interbank Transfers (IMPS / NEFT / RTGS)

How a customer of this bank sends money to an account at another bank.

## Example

Asha sends ₹10,000 from `ACC1001` to Meena's account `123456789012` at HDFC (IFSC `HDFC0001234`) by IMPS.

```http
POST /api/v1/interbank-transfers
Authorization: Bearer <Asha's token>
Idempotency-Key: 3f6c0d1e-...

{
  "fromAccount": "ACC1001",
  "beneficiaryName": "Meena Iyer",
  "beneficiaryAccount": "123456789012",
  "beneficiaryIfsc": "HDFC0001234",
  "rail": "IMPS",
  "amount": 10000,
  "currency": "INR",
  "reference": "Invoice 42"
}
```

The answer is `202 Accepted` with `"status": "PENDING"`. The money has already left Asha's account, but the
other bank has not confirmed yet. The app checks `GET /api/v1/interbank-transfers/{id}` (or waits for the SMS/email)
until the status is `SETTLED` (with a UTR number) or `REFUNDED`.

## Why it is different from a transfer inside the bank

Inside the bank, both accounts are in our database, so the debit and credit happen in one database transaction.
For another bank we only control our side. The other bank answers later, and sometimes the answer is lost.
So the transfer is a small state machine:

```
             our checks fail                        clearing network accepts
 request ──────────────────▶ FAILED (no money moved)
    │
    │ debit customer, hold money in GL-IBT-SUSPENSE
    ▼
 PENDING ──────────────────────────────────────────▶ SETTLED   (SUSPENSE → GL-IBT-CLEARED, UTR saved)
    │
    │ network / beneficiary bank rejects
    ▼
 REFUNDED (SUSPENSE → customer account)
```

## Step by step

| Step | Service | What happens |
|---|---|---|
| 1 | API Gateway | Checks the token and rate limit, adds the correlation ID. |
| 2 | Account Service | Checks the request, the payment rail rules, that the IFSC is not our own bank, the duplicate key and that the customer is eligible (customer-service). |
| 3 | Account Service | **One database transaction:** locks the customer account and the suspense account, checks balance and daily limit, debits the customer, credits `GL-IBT-SUSPENSE`, writes 2 ledger lines, saves the transfer as `PENDING`, writes `InterbankTransferInitiated` to the outbox. |
| 4 | Payment Service | Reads the event and creates a `clearing_instruction`. IMPS and RTGS are sent at once; NEFT waits for the next batch (every 30 minutes). |
| 5 | Payment Service | Sends the instruction to the clearing network, using the transfer ID as the network reference. |
| 6a | Payment Service | **Accepted:** publishes `InterbankClearingSettled` (with UTR) on `payment-events`. |
| 6b | Payment Service | **Rejected** (for example the account does not exist): publishes `InterbankClearingRejected`. |
| 6c | Payment Service | **No answer** (timeout or network down): status becomes `UNKNOWN`. It tries again after 1s, 2s, 4s ... up to 1 minute. Each try **first asks the network for the status** and only resubmits if the network never got it. After 10 tries it goes to `MANUAL_REVIEW` for the operations team. It is **never refunded automatically**, because the money may already be at the other bank. |
| 7 | Account Service | Reads `payment-events`. Settled: moves the money from SUSPENSE to `GL-IBT-CLEARED` and marks the transfer `SETTLED`. Rejected: returns the money from SUSPENSE to the customer and marks it `REFUNDED`. A second result for the same transfer is ignored. |
| 8 | Transaction / Notification | Statement line for the debit (and the refund credit), audit records for every event, and SMS/email: "debited, in process", then "credited, UTR ..." or "failed, refunded". |

## Payment rails

| Rail | Speed | Amount rule (config `banking.interbank`) |
|---|---|---|
| IMPS | Instant, 24x7 | Up to ₹5,00,000 per transfer |
| NEFT | Half-hourly batches (`NEFT_BATCH_INTERVAL`, 30 min) | Any amount |
| RTGS | Instant | At least ₹2,00,000 |

All rails: at most ₹10,00,000 per transfer. The customer's daily limit (₹5,00,000) counts internal transfers and
interbank transfers that are pending or settled; refunded ones do not count.

## Safety rules

- **No double debit:** same `Idempotency-Key` + same body returns the original transfer (`Idempotent-Replayed: true`); a different body is rejected.
- **No double payout:** the network reference is the transfer ID, and the network dedupes on it. After a timeout we ask the status before sending again.
- **No lost money:** every movement is a pair of ledger lines. `GL-IBT-SUSPENSE` equals the total of `PENDING` transfers at any time, and `GL-IBT-CLEARED` is reconciled every day with the RBI settlement statement.
- **Events are never lost:** both services use the transactional outbox, and both consumers use the inbox (`processed_message`) to skip duplicates.
- **No deadlocks:** accounts are always locked in account-number order, the same order internal transfers use.

## Events

| Topic | Event | Producer → consumers |
|---|---|---|
| `account-events` | `InterbankTransferInitiated` | account → payment, transaction, notification |
| `payment-events` | `InterbankClearingSettled` / `InterbankClearingRejected` | payment → account |
| `account-events` | `InterbankTransferSettled` / `InterbankTransferRefunded` | account → transaction, notification |
| `account-events` | `TransferFailed` (rejected by our checks) | account → payment, transaction, notification |

## Trying it locally

`clearing-simulator` stands in for NPCI/RBI and the other bank. It is local only and is not deployed to production.
It picks its answer from the beneficiary account number:

| Beneficiary account ends with | Result |
|---|---|
| `0000` | Rejected: account does not exist → customer refunded |
| `9999` | Accepted, but the first reply is slower than our 3s timeout → status inquiry → settled, sent only once |
| anything else | Accepted with a UTR |

```bash
docker compose up -d --build
./scripts/e2e-interbank.sh
```

Status of the clearing side: `GET /api/v1/payments/interbank/{transferId}` (status, attempts, UTR).

## Not covered yet

- Money coming **in** from other banks (inward credits).
- Transfer charges and GST.
- RTGS business hours and bank holidays.
- Beneficiary registration with cooling period, and name lookup before sending.
- A real NPCI / RBI connector (ISO 20022 messages, mTLS, signing). The `ClearingClient` HTTP adapter is where it plugs in.
- `GL-IBT-SUSPENSE` is one database row that every interbank transfer locks. At very high volume it should be split into several suspense accounts.
