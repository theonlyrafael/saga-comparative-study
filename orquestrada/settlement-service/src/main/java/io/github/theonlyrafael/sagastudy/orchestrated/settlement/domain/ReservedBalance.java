package io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/** Snapshot supplied after a successful reservation; not proof obtained from the account database. */
public record ReservedBalance(UUID reservationId, UUID orderId, UUID sagaId, String buyerId, BigDecimal amount) {
    public ReservedBalance {
        Objects.requireNonNull(reservationId, "reservationId is required");
        Objects.requireNonNull(orderId, "orderId is required");
        Objects.requireNonNull(sagaId, "sagaId is required");
        if (buyerId == null || buyerId.isBlank() || buyerId.length() > 64) {
            throw new IllegalArgumentException("buyerId must contain between 1 and 64 characters");
        }
        Objects.requireNonNull(amount, "amount is required");
        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("amount must not contain fractions of a cent", error);
        }
        if (amount.signum() <= 0 || amount.precision() > 19) {
            throw new IllegalArgumentException("amount must be positive and fit NUMERIC(19,2)");
        }
    }
}
