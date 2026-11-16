package io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain;

import java.util.Objects;
import java.util.UUID;

/** Snapshot of a successful asset reservation supplied by trusted Saga coordination. */
public record ReservedAsset(UUID reservationId, UUID orderId, UUID sagaId, String buyerId, String assetId, int quantity) {
    public ReservedAsset {
        Objects.requireNonNull(reservationId, "reservationId is required");
        Objects.requireNonNull(orderId, "orderId is required");
        Objects.requireNonNull(sagaId, "sagaId is required");
        if (buyerId == null || buyerId.isBlank() || buyerId.length() > 64) {
            throw new IllegalArgumentException("buyerId must contain between 1 and 64 characters");
        }
        if (assetId == null || assetId.isBlank()) {
            throw new IllegalArgumentException("assetId is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
