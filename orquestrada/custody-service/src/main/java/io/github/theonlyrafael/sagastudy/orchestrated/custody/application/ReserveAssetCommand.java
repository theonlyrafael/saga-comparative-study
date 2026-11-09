package io.github.theonlyrafael.sagastudy.orchestrated.custody.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Local input for reserving an asset from the configured counterparty for a buyer.
 * The orchestrator must confirm a compatible balance reservation before requesting
 * this operation. Constructing this command only validates input; it does not
 * verify balance or change stock.
 */
public record ReserveAssetCommand(
        UUID orderId,
        UUID sagaId,
        UUID operationId,
        String buyerId,
        String assetId,
        int quantity
) {

    public ReserveAssetCommand {
        Objects.requireNonNull(orderId, "orderId is required");
        Objects.requireNonNull(sagaId, "sagaId is required");
        Objects.requireNonNull(operationId, "operationId is required");
        requireIdentifier(buyerId, "buyerId");
        requireIdentifier(assetId, "assetId");

        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
