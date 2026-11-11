CREATE TABLE asset_reservations (
    reservation_id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    saga_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    source_position_id UUID NOT NULL,
    buyer_id TEXT NOT NULL,
    asset_id TEXT NOT NULL,
    quantity INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT fk_asset_reservations_source
        FOREIGN KEY (source_position_id) REFERENCES positions (position_id),
    CONSTRAINT uk_asset_reservations_order UNIQUE (order_id),
    CONSTRAINT uk_asset_reservations_saga UNIQUE (saga_id),
    CONSTRAINT uk_asset_reservations_operation UNIQUE (operation_id),
    CONSTRAINT ck_asset_reservations_quantity CHECK (quantity > 0),
    CONSTRAINT ck_asset_reservations_status
        CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED')),
    CONSTRAINT ck_asset_reservations_completion CHECK (
        (status = 'RESERVED' AND completed_at IS NULL)
        OR (status IN ('CONFIRMED', 'RELEASED') AND completed_at IS NOT NULL)
    )
);

CREATE INDEX ix_asset_reservations_source ON asset_reservations (source_position_id);
