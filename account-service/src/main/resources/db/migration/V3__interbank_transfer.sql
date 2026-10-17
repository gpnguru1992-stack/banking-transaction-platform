-- Outgoing transfers to accounts at other banks (IMPS / NEFT / RTGS).
CREATE TABLE interbank_transfer (
    id                  UUID PRIMARY KEY,
    customer_id         VARCHAR(20)    NOT NULL,
    idempotency_key     VARCHAR(64)    NOT NULL,
    request_hash        VARCHAR(64)    NOT NULL,
    from_account        VARCHAR(20)    NOT NULL,
    beneficiary_name    VARCHAR(100)   NOT NULL,
    beneficiary_account VARCHAR(18)    NOT NULL,
    beneficiary_ifsc    VARCHAR(11)    NOT NULL,
    rail                VARCHAR(10)    NOT NULL,
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency            VARCHAR(3)     NOT NULL,
    reference           VARCHAR(140),
    status              VARCHAR(20)    NOT NULL,
    utr                 VARCHAR(40),
    failure_code        VARCHAR(50),
    failure_reason      VARCHAR(500),
    created_at          TIMESTAMPTZ    NOT NULL,
    updated_at          TIMESTAMPTZ    NOT NULL,
    completed_at        TIMESTAMPTZ,
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ux_interbank_idempotency UNIQUE (customer_id, idempotency_key)
);
CREATE INDEX idx_interbank_from_day ON interbank_transfer (from_account, created_at) WHERE status IN ('PENDING', 'SETTLED');
CREATE INDEX idx_interbank_pending ON interbank_transfer (created_at) WHERE status = 'PENDING';

-- Bank-owned ledger accounts. Money for an outgoing payment sits in SUSPENSE until the clearing network
-- answers, then moves to CLEARED (paid out; reconciled against the RBI settlement statement) or back to the customer.
INSERT INTO account (account_number, customer_id, account_type, currency, balance, status, created_at, updated_at)
VALUES ('GL-IBT-SUSPENSE', 'BANK', 'INTERNAL', 'INR', 0, 'ACTIVE', now(), now()),
       ('GL-IBT-CLEARED', 'BANK', 'INTERNAL', 'INR', 0, 'ACTIVE', now(), now());
