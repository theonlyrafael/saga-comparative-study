CREATE TABLE accounts (
    account_id VARCHAR(64) PRIMARY KEY,
    available_balance NUMERIC(19, 2) NOT NULL,
    reserved_balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    total_consumed NUMERIC(19, 2) NOT NULL DEFAULT 0,

    CONSTRAINT ck_accounts_available_nonnegative
        CHECK (available_balance >= 0),

    CONSTRAINT ck_accounts_reserved_nonnegative
        CHECK (reserved_balance >= 0),

    CONSTRAINT ck_accounts_consumed_nonnegative
        CHECK (total_consumed >= 0)
);

CREATE TABLE balance_reservations (
    reservation_id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    saga_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT fk_balance_reservations_account
        FOREIGN KEY (account_id)
        REFERENCES accounts (account_id),

    CONSTRAINT uk_balance_reservations_order
        UNIQUE (order_id),

    CONSTRAINT uk_balance_reservations_saga
        UNIQUE (saga_id),

    CONSTRAINT uk_balance_reservations_operation
        UNIQUE (operation_id),

    CONSTRAINT ck_balance_reservations_positive_amount
        CHECK (amount > 0),

    CONSTRAINT ck_balance_reservations_status
        CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED')),

    CONSTRAINT ck_balance_reservations_completion
        CHECK (
            (status = 'RESERVED' AND completed_at IS NULL)
            OR
            (status IN ('CONFIRMED', 'RELEASED') AND completed_at IS NOT NULL)
        )
);

CREATE INDEX ix_balance_reservations_account
    ON balance_reservations (account_id);
