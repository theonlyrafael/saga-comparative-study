ALTER TABLE orders
    ADD COLUMN settlement_id UUID,
    ADD COLUMN rejection_reason TEXT,
    ADD CONSTRAINT ck_order_outcome CHECK (
        (status = 'RECEBIDA' AND settlement_id IS NULL AND rejection_reason IS NULL)
        OR (status = 'LIQUIDADA' AND settlement_id IS NOT NULL AND rejection_reason IS NULL)
        OR (status = 'REJEITADA' AND settlement_id IS NULL AND rejection_reason IS NOT NULL)
    ) NOT VALID;

CREATE TABLE inbox_messages (
    consumer_name TEXT NOT NULL,
    message_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_name, message_id),
    UNIQUE (consumer_name, operation_id)
);

CREATE TABLE inbox_deliveries (
    consumer_name TEXT NOT NULL,
    message_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    PRIMARY KEY (consumer_name, message_id),
    FOREIGN KEY (consumer_name, operation_id) REFERENCES inbox_messages (consumer_name, operation_id)
);

ALTER TABLE outbox_messages
    ADD COLUMN consumer_name TEXT,
    ADD COLUMN operation_id UUID,
    ADD COLUMN request JSONB,
    ADD UNIQUE (consumer_name, operation_id),
    ADD FOREIGN KEY (consumer_name, operation_id) REFERENCES inbox_messages (consumer_name, operation_id);

CREATE UNIQUE INDEX uq_outbox_final_outcome ON outbox_messages (order_id)
    WHERE message_type IN ('OrdemLiquidada', 'OrdemRejeitada');
