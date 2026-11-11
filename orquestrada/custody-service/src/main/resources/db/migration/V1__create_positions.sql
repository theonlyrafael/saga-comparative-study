CREATE TABLE positions (
    position_id UUID PRIMARY KEY,
    owner_id TEXT NOT NULL,
    asset_id TEXT NOT NULL,
    available_quantity BIGINT NOT NULL DEFAULT 0,
    reserved_quantity BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uk_positions_owner_asset
        UNIQUE (owner_id, asset_id),

    CONSTRAINT ck_positions_available_nonnegative
        CHECK (available_quantity >= 0),

    CONSTRAINT ck_positions_reserved_nonnegative
        CHECK (reserved_quantity >= 0)
);
