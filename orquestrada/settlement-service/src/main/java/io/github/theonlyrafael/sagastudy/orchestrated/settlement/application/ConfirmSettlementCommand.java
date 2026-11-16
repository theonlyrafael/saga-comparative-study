package io.github.theonlyrafael.sagastudy.orchestrated.settlement.application;

import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.ReservedAsset;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.ReservedBalance;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/** Internal input; the captured price and catalogue version come from the original order. */
public record ConfirmSettlementCommand(
        UUID operationId, ReservedBalance balance, ReservedAsset asset,
        BigDecimal unitPrice, String catalogVersion
) {
    public ConfirmSettlementCommand {
        Objects.requireNonNull(operationId, "operationId is required");
        Objects.requireNonNull(balance, "balance is required");
        Objects.requireNonNull(asset, "asset is required");
        Objects.requireNonNull(unitPrice, "unitPrice is required");
        if (catalogVersion == null || catalogVersion.isBlank()) {
            throw new IllegalArgumentException("catalogVersion is required");
        }
        try {
            unitPrice = unitPrice.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("unitPrice must not contain fractions of a cent", error);
        }
        if (unitPrice.signum() <= 0 || unitPrice.precision() > 19) {
            throw new IllegalArgumentException("unitPrice must be positive and fit NUMERIC(19,2)");
        }
    }
}
