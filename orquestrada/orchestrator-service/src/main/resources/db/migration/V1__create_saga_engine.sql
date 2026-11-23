CREATE TABLE saga_instances (
    saga_id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE,
    state TEXT NOT NULL CHECK (state IN (
        'RESERVANDO_SALDO', 'RESERVANDO_ATIVO', 'CONFIRMANDO_LIQUIDACAO', 'EFETIVANDO',
        'COMPENSANDO', 'RECUPERACAO_PENDENTE', 'CONCLUIDA', 'REJEITADA')),
    snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE saga_steps (
    operation_id UUID PRIMARY KEY,
    saga_id UUID NOT NULL REFERENCES saga_instances (saga_id),
    command_type TEXT NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    response_type TEXT,
    completed_at TIMESTAMPTZ,
    UNIQUE (saga_id, command_type),
    CHECK ((response_type IS NULL) = (completed_at IS NULL))
);

CREATE TABLE outbox_messages (
    message_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE REFERENCES saga_steps (operation_id),
    message_type TEXT NOT NULL,
    envelope JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);

CREATE INDEX ix_outbox_ready ON outbox_messages (next_attempt_at, created_at, message_id)
    WHERE published_at IS NULL;

CREATE TABLE inbox_operations (
    operation_id UUID PRIMARY KEY,
    saga_id UUID NOT NULL REFERENCES saga_instances (saga_id) DEFERRABLE INITIALLY DEFERRED,
    content JSONB NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE inbox_deliveries (
    message_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES inbox_operations (operation_id)
);
