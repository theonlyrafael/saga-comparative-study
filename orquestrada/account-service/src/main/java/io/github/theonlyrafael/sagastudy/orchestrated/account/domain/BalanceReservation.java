package io.github.theonlyrafael.sagastudy.orchestrated.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "balance_reservations")
public class BalanceReservation {

    @Id
    @Column(name = "reservation_id", nullable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "saga_id", nullable = false, updatable = false)
    private UUID sagaId;

    @Column(name = "operation_id", nullable = false, updatable = false)
    private UUID operationId;

    @Column(name = "account_id", length = 64, nullable = false, updatable = false)
    private String accountId;

    @Column(
            name = "amount",
            precision = 19,
            scale = 2,
            nullable = false,
            updatable = false
    )
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private BalanceReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected BalanceReservation() {
        // Required by JPA.
    }

    public static BalanceReservation reserve(
            UUID orderId,
            UUID sagaId,
            UUID operationId,
            String accountId,
            BigDecimal amount
    ) {
        if (orderId == null || sagaId == null || operationId == null) {
            throw new IllegalArgumentException("Reservation identifiers are required");
        }

        if (accountId == null || accountId.isBlank() || accountId.length() > 64) {
            throw new IllegalArgumentException("Invalid accountId");
        }

        if (amount == null
                || amount.signum() <= 0
                || amount.scale() != 2
                || amount.precision() > 19) {
            throw new IllegalArgumentException(
                    "amount must be a positive monetary value with scale 2"
            );
        }

        BalanceReservation reservation = new BalanceReservation();
        reservation.id = UUID.randomUUID();
        reservation.orderId = orderId;
        reservation.sagaId = sagaId;
        reservation.operationId = operationId;
        reservation.accountId = accountId;
        reservation.amount = amount;
        reservation.status = BalanceReservationStatus.RESERVED;
        reservation.createdAt = Instant.now();

        return reservation;
    }

    public boolean matches(
            UUID expectedOrderId,
            UUID expectedSagaId,
            String expectedAccountId,
            BigDecimal expectedAmount
    ) {
        return orderId.equals(expectedOrderId)
                && sagaId.equals(expectedSagaId)
                && accountId.equals(expectedAccountId)
                && amount.compareTo(expectedAmount) == 0;
    }

    public void confirm() {
        requireReserved();
        status = BalanceReservationStatus.CONFIRMED;
        completedAt = Instant.now();
    }

    public void release() {
        requireReserved();
        status = BalanceReservationStatus.RELEASED;
        completedAt = Instant.now();
    }

    private void requireReserved() {
        if (status != BalanceReservationStatus.RESERVED) {
            throw new BalanceReservationException(
                    BalanceReservationException.Reason.INVALID_TRANSITION,
                    "Only a reserved balance can change to a terminal state"
            );
        }
    }

    public UUID getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BalanceReservationStatus getStatus() {
        return status;
    }
}
