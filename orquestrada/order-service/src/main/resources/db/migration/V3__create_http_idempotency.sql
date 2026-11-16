CREATE TABLE http_idempotency (
    cliente_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    ativo_id VARCHAR(128) NOT NULL,
    quantidade INTEGER NOT NULL CHECK (quantidade > 0),
    order_id UUID NOT NULL UNIQUE,
    PRIMARY KEY (cliente_id, idempotency_key),
    FOREIGN KEY (order_id) REFERENCES orders(order_id) DEFERRABLE INITIALLY DEFERRED
);
