CREATE TABLE inbox_messages (
    consumer_name TEXT NOT NULL CHECK (btrim(consumer_name) <> ''),
    message_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_name, message_id),
    UNIQUE (consumer_name, operation_id)
);
