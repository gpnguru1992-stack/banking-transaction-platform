CREATE SEQUENCE account_number_seq START WITH 3001;

CREATE TABLE account (
    id             BIGSERIAL PRIMARY KEY,
    account_number VARCHAR(20)    NOT NULL UNIQUE,
    customer_id    VARCHAR(20)    NOT NULL,
    account_type   VARCHAR(20)    NOT NULL,
    currency       CHAR(3)        NOT NULL,
    balance        NUMERIC(19, 2) NOT NULL CHECK (balance >= 0),
    status         VARCHAR(20)    NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL,
    updated_at     TIMESTAMPTZ    NOT NULL,
    version        BIGINT         NOT NULL DEFAULT 0
);
CREATE INDEX idx_account_customer ON account (customer_id);

-- Append-only double-entry ledger.
CREATE TABLE ledger_entry (
    id             UUID PRIMARY KEY,
    account_number VARCHAR(20)    NOT NULL REFERENCES account (account_number),
    reference_id   UUID           NOT NULL,
    source         VARCHAR(10)    NOT NULL,
    entry_type     VARCHAR(10)    NOT NULL,
    amount         NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    balance_after  NUMERIC(19, 2) NOT NULL,
    currency       CHAR(3)        NOT NULL,
    narrative      VARCHAR(140),
    created_at     TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ux_ledger_reference UNIQUE (reference_id, account_number, entry_type)
);
CREATE INDEX idx_ledger_account_time ON ledger_entry (account_number, created_at DESC);

CREATE RULE ledger_entry_no_update AS ON UPDATE TO ledger_entry DO INSTEAD NOTHING;
CREATE RULE ledger_entry_no_delete AS ON DELETE TO ledger_entry DO INSTEAD NOTHING;

CREATE TABLE transfer (
    id              UUID PRIMARY KEY,
    customer_id     VARCHAR(20)    NOT NULL,
    idempotency_key VARCHAR(64)    NOT NULL,
    request_hash    CHAR(64)       NOT NULL,
    from_account    VARCHAR(20)    NOT NULL,
    to_account      VARCHAR(20)    NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency        CHAR(3)        NOT NULL,
    reference       VARCHAR(140),
    status          VARCHAR(20)    NOT NULL,
    failure_code    VARCHAR(50),
    failure_reason  VARCHAR(500),
    created_at      TIMESTAMPTZ    NOT NULL,
    completed_at    TIMESTAMPTZ,
    CONSTRAINT ux_transfer_idempotency UNIQUE (customer_id, idempotency_key)
);
CREATE INDEX idx_transfer_from_day ON transfer (from_account, created_at) WHERE status = 'COMPLETED';
