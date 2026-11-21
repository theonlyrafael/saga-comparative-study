-- A retried operation may arrive in a new envelope. Bind every accepted ID to its operation.
CREATE TABLE inbox_deliveries (
    consumer_name TEXT NOT NULL,
    message_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    PRIMARY KEY (consumer_name, message_id),
    FOREIGN KEY (consumer_name, operation_id) REFERENCES inbox_messages (consumer_name, operation_id)
);

INSERT INTO inbox_deliveries (consumer_name, message_id, operation_id)
SELECT consumer_name, message_id, operation_id FROM inbox_messages;
