-- One row per interbank transfer sent to the clearing network (IMPS / NEFT / RTGS).
CREATE TABLE clearing_instruction (
    transfer_id         UUID PRIMARY KEY,
    customer_id         VARCHAR(20)    NOT NULL,
    from_account        VARCHAR(20)    NOT NULL,
    beneficiary_name    VARCHAR(100)   NOT NULL,
    beneficiary_account VARCHAR(18)    NOT NULL,
    beneficiary_ifsc    VARCHAR(11)    NOT NULL,
    rail                VARCHAR(10)    NOT NULL,
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency            VARCHAR(3)     NOT NULL,
    reference           VARCHAR(140),
    status              VARCHAR(20)    NOT NULL,
    attempts            INT            NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ    NOT NULL,
    utr                 VARCHAR(40),
    failure_code        VARCHAR(50),
    failure_reason      VARCHAR(500),
    last_error          VARCHAR(500),
    correlation_id      VARCHAR(64),
    created_at          TIMESTAMPTZ    NOT NULL,
    updated_at          TIMESTAMPTZ    NOT NULL,
    completed_at        TIMESTAMPTZ,
    version             BIGINT         NOT NULL DEFAULT 0
);
CREATE INDEX idx_clearing_due ON clearing_instruction (next_attempt_at) WHERE status IN ('QUEUED', 'UNKNOWN');
CREATE INDEX idx_clearing_customer ON clearing_instruction (customer_id, created_at DESC);
