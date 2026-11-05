package io.github.theonlyrafael.sagastudy.orchestrated.account.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

public record ReserveBalanceCommand(
        UUID orderId,
        UUID sagaId,
        UUID operationId,
        String accountId,
        BigDecimal amount
) {

    public ReserveBalanceCommand {
        Objects.requireNonNull(orderId, "orderId is required");
        Objects.requireNonNull(sagaId, "sagaId is required");
        Objects.requireNonNull(operationId, "operationId is required");
        Objects.requireNonNull(amount, "amount is required");

        if (accountId == null || accountId.isBlank() || accountId.length() > 64) {
            throw new IllegalArgumentException(
                    "accountId must contain between 1 and 64 characters"
            );
        }

        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "amount must not contain fractions of a cent",
                    exception
            );
        }

        if (amount.signum() <= 0 || amount.precision() > 19) {
            throw new IllegalArgumentException(
                    "amount must be positive and fit NUMERIC(19,2)"
            );
        }
    }
}
