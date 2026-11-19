CREATE TABLE outbox_messages (
    message_id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(order_id),
    message_type TEXT NOT NULL,
    envelope JSONB NOT NULL CHECK (jsonb_typeof(envelope) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_outbox_order_received ON outbox_messages (order_id)
    WHERE message_type = 'OrdemRecebida';

CREATE INDEX ix_outbox_pending ON outbox_messages (created_at, message_id)
    WHERE published_at IS NULL;
