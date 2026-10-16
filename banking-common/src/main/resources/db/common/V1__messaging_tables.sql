-- Transactional outbox: events committed atomically with business data, relayed to Kafka.
CREATE TABLE outbox_event (
    id             UUID PRIMARY KEY,
    topic          VARCHAR(100)  NOT NULL,
    message_key    VARCHAR(100)  NOT NULL,
    event_type     VARCHAR(100)  NOT NULL,
    aggregate_type VARCHAR(100)  NOT NULL,
    aggregate_id   VARCHAR(100)  NOT NULL,
    payload        TEXT          NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    attempts       INT           NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000),
    created_at     TIMESTAMPTZ   NOT NULL,
    published_at   TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox_event (created_at) WHERE status = 'PENDING';

-- Inbox: ids of consumed events, for idempotent (exactly-once effect) processing.
CREATE TABLE processed_message (
    event_id     UUID         NOT NULL,
    consumer     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (event_id, consumer)
);
