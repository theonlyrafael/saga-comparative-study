-- Both outcomes compete for the same five correlation keys before the service can reply.
CREATE TABLE settlement_decisions (
    order_id UUID PRIMARY KEY,
    saga_id UUID NOT NULL UNIQUE,
    operation_id UUID NOT NULL UNIQUE,
    balance_reservation_id UUID NOT NULL UNIQUE,
    asset_reservation_id UUID NOT NULL UNIQUE,
    outcome TEXT NOT NULL CHECK (outcome IN ('CONFIRMED', 'DENIED')),
    UNIQUE (order_id, outcome)
);

INSERT INTO settlement_decisions
    (order_id, saga_id, operation_id, balance_reservation_id, asset_reservation_id, outcome)
SELECT order_id, saga_id, operation_id, balance_reservation_id, asset_reservation_id, status FROM settlements;

ALTER TABLE settlements ADD CONSTRAINT fk_settlements_confirmed_decision
    FOREIGN KEY (order_id, status) REFERENCES settlement_decisions (order_id, outcome);
