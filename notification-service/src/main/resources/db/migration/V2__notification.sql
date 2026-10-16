CREATE TABLE customer_contact (
    customer_id     VARCHAR(20) PRIMARY KEY,
    full_name       VARCHAR(200) NOT NULL,
    email           VARCHAR(254),
    phone           VARCHAR(20),
    email_opt_in    BOOLEAN      NOT NULL,
    sms_opt_in      BOOLEAN      NOT NULL,
    source_event_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE notification (
    id              UUID PRIMARY KEY,
    event_id        UUID          NOT NULL,
    customer_id     VARCHAR(20)   NOT NULL,
    channel         VARCHAR(10)   NOT NULL,
    subject         VARCHAR(200)  NOT NULL,
    body            VARCHAR(1000) NOT NULL,
    recipient       VARCHAR(254),
    status          VARCHAR(10)   NOT NULL,
    attempts        INT           NOT NULL DEFAULT 0,
    last_error      VARCHAR(500),
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMPTZ   NOT NULL,
    next_attempt_at TIMESTAMPTZ   NOT NULL,
    sent_at         TIMESTAMPTZ,
    CONSTRAINT ux_notification UNIQUE (event_id, customer_id, channel)
);
CREATE INDEX idx_notification_due ON notification (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_notification_customer ON notification (customer_id, created_at DESC);
