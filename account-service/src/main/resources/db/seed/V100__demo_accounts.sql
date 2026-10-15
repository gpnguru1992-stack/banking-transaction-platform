-- Demo data only (profile "demo"): ACC1001 for Asha (CUST1001) and ACC2001 for Ravi (CUST1002).
INSERT INTO account (account_number, customer_id, account_type, currency, balance, status, created_at, updated_at)
VALUES ('ACC1001', 'CUST1001', 'SAVINGS', 'INR', 100000.00, 'ACTIVE', now(), now()),
       ('ACC2001', 'CUST1002', 'SAVINGS', 'INR', 25000.00, 'ACTIVE', now(), now());

INSERT INTO ledger_entry (id, account_number, reference_id, source, entry_type, amount, balance_after, currency, narrative, created_at)
SELECT gen_random_uuid(), account_number, gen_random_uuid(), 'DEPOSIT', 'CREDIT', balance, balance, currency, 'Opening balance', now()
FROM account;
