CREATE TABLE payment (
    transfer_id             UUID PRIMARY KEY,
    customer_id             VARCHAR(20)    NOT NULL,
    beneficiary_customer_id VARCHAR(20),
    from_account            VARCHAR(20)    NOT NULL,
    to_account              VARCHAR(20)    NOT NULL,
    amount                  NUMERIC(19, 2) NOT NULL,
    currency                CHAR(3)        NOT NULL,
    reference               VARCHAR(140),
    status                  VARCHAR(20)    NOT NULL,
    failure_code            VARCHAR(50),
    failure_reason          VARCHAR(500),
    business_date           DATE           NOT NULL,
    occurred_at             TIMESTAMPTZ    NOT NULL,
    recorded_at             TIMESTAMPTZ    NOT NULL
);
CREATE INDEX idx_payment_customer_time ON payment (customer_id, occurred_at DESC);
CREATE INDEX idx_payment_business_date ON payment (business_date);
