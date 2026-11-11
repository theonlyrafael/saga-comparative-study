package io.github.theonlyrafael.sagastudy.orchestrated.custody.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

@Entity
@Table(name = "positions", uniqueConstraints = @UniqueConstraint(
        name = "uk_positions_owner_asset", columnNames = {"owner_id", "asset_id"}
))
public class Position {

    @Id
    @Column(name = "position_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false, columnDefinition = "text")
    private String ownerId;

    @Column(name = "asset_id", nullable = false, updatable = false, columnDefinition = "text")
    private String assetId;

    @Column(name = "available_quantity", nullable = false)
    private long availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private long reservedQuantity;

    protected Position() {
        // Required by JPA. Quantities are changed through conditional repository updates.
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getAssetId() {
        return assetId;
    }

    public long getAvailableQuantity() {
        return availableQuantity;
    }

    public long getReservedQuantity() {
        return reservedQuantity;
    }
}
