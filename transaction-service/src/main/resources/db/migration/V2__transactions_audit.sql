CREATE TABLE transaction_record (
    id                   UUID PRIMARY KEY,
    reference_id         UUID           NOT NULL,
    account_number       VARCHAR(20)    NOT NULL,
    customer_id          VARCHAR(20)    NOT NULL,
    counterparty_account VARCHAR(20),
    type                 VARCHAR(20)    NOT NULL,
    direction            VARCHAR(10)    NOT NULL,
    amount               NUMERIC(19, 2) NOT NULL,
    currency             CHAR(3)        NOT NULL,
    balance_after        NUMERIC(19, 2),
    status               VARCHAR(20)    NOT NULL,
    description          VARCHAR(500),
    occurred_at          TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ux_transaction_line UNIQUE (reference_id, account_number, direction)
);
CREATE INDEX idx_transaction_account_time ON transaction_record (account_number, occurred_at DESC);

CREATE TABLE audit_log (
    event_id       UUID PRIMARY KEY,
    event_type     VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    producer       VARCHAR(100) NOT NULL,
    correlation_id VARCHAR(64),
    topic          VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    recorded_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_audit_aggregate ON audit_log (aggregate_id, occurred_at DESC);
CREATE INDEX idx_audit_correlation ON audit_log (correlation_id);

-- The audit trail is append-only.
CREATE RULE audit_log_no_update AS ON UPDATE TO audit_log DO INSTEAD NOTHING;
CREATE RULE audit_log_no_delete AS ON DELETE TO audit_log DO INSTEAD NOTHING;
