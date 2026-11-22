CREATE TABLE outbox_messages (
    message_id UUID PRIMARY KEY,
    consumer_name TEXT NOT NULL,
    operation_id UUID NOT NULL,
    message_type TEXT NOT NULL,
    request JSONB NOT NULL,
    envelope JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    UNIQUE (consumer_name, operation_id),
    FOREIGN KEY (consumer_name, operation_id) REFERENCES inbox_messages (consumer_name, operation_id)
);

CREATE INDEX ix_outbox_ready ON outbox_messages (next_attempt_at, created_at, message_id)
    WHERE published_at IS NULL;
