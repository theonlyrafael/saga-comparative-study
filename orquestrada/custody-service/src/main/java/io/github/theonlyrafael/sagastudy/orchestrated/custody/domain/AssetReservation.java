package io.github.theonlyrafael.sagastudy.orchestrated.custody.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "asset_reservations")
public class AssetReservation {

    @Id
    @Column(name = "reservation_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "saga_id", nullable = false, updatable = false)
    private UUID sagaId;

    @Column(name = "operation_id", nullable = false, updatable = false)
    private UUID operationId;

    @Column(name = "source_position_id", nullable = false, updatable = false)
    private UUID sourcePositionId;

    @Column(name = "buyer_id", nullable = false, updatable = false, columnDefinition = "text")
    private String buyerId;

    @Column(name = "asset_id", nullable = false, updatable = false, columnDefinition = "text")
    private String assetId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AssetReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected AssetReservation() {
        // Required by JPA.
    }

    public static AssetReservation reserve(
            UUID orderId, UUID sagaId, UUID operationId, UUID sourcePositionId,
            String buyerId, String assetId, int quantity
    ) {
        Objects.requireNonNull(orderId, "orderId is required");
        Objects.requireNonNull(sagaId, "sagaId is required");
        Objects.requireNonNull(operationId, "operationId is required");
        Objects.requireNonNull(sourcePositionId, "sourcePositionId is required");
        if (buyerId == null || buyerId.isBlank() || assetId == null || assetId.isBlank()) {
            throw new IllegalArgumentException("buyerId and assetId are required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }

        var reservation = new AssetReservation();
        reservation.id = UUID.randomUUID();
        reservation.orderId = orderId;
        reservation.sagaId = sagaId;
        reservation.operationId = operationId;
        reservation.sourcePositionId = sourcePositionId;
        reservation.buyerId = buyerId;
        reservation.assetId = assetId;
        reservation.quantity = quantity;
        reservation.status = AssetReservationStatus.RESERVED;
        reservation.createdAt = Instant.now();
        return reservation;
    }

    public boolean matches(UUID orderId, UUID sagaId, String buyerId, String assetId, int quantity) {
        return this.orderId.equals(orderId) && this.sagaId.equals(sagaId)
                && this.buyerId.equals(buyerId) && this.assetId.equals(assetId)
                && this.quantity == quantity;
    }

    public void confirm() {
        requireReserved();
        status = AssetReservationStatus.CONFIRMED;
        completedAt = Instant.now();
    }

    public void release() {
        requireReserved();
        status = AssetReservationStatus.RELEASED;
        completedAt = Instant.now();
    }

    private void requireReserved() {
        if (status != AssetReservationStatus.RESERVED) {
            throw new AssetReservationException(AssetReservationException.Reason.INVALID_TRANSITION,
                    "Only a reserved asset can change to a terminal state");
        }
    }

    public UUID getId() { return id; }
    public UUID getSourcePositionId() { return sourcePositionId; }
    public String getBuyerId() { return buyerId; }
    public String getAssetId() { return assetId; }
    public int getQuantity() { return quantity; }
    public AssetReservationStatus getStatus() { return status; }
}
