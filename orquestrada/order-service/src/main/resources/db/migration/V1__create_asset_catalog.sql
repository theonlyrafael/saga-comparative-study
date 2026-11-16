CREATE TABLE asset_catalog (
    asset_id VARCHAR(128) PRIMARY KEY CHECK (length(trim(asset_id)) > 0),
    catalog_version VARCHAR(128) NOT NULL CHECK (length(trim(catalog_version)) > 0),
    unit_price NUMERIC(19,2) NOT NULL CHECK (unit_price > 0)
);
