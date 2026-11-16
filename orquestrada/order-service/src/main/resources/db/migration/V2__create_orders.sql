CREATE TABLE orders (
    order_id UUID PRIMARY KEY,
    saga_id UUID NOT NULL UNIQUE,
    cliente_id VARCHAR(64) NOT NULL,
    ativo_id VARCHAR(128) NOT NULL,
    quantidade INTEGER NOT NULL CHECK (quantidade > 0),
    unit_price NUMERIC(19,2) NOT NULL CHECK (unit_price > 0),
    total NUMERIC(19,2) NOT NULL CHECK (total > 0 AND total = unit_price * quantidade),
    catalog_version VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RECEBIDA', 'LIQUIDADA', 'REJEITADA')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
