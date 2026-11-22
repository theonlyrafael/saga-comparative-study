package io.github.theonlyrafael.sagastudy.orchestrated.custody.application;

import io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservation;
import io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationRefusal;
import io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationException;
import io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationStatus;
import io.github.theonlyrafael.sagastudy.orchestrated.custody.repository.AssetReservationRepository;
import io.github.theonlyrafael.sagastudy.orchestrated.custody.repository.PositionRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationException.Reason.INSUFFICIENT_ASSETS;
import static io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationException.Reason.INVALID_TRANSITION;
import static io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationException.Reason.OPERATION_CONFLICT;
import static io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationException.Reason.POSITION_NOT_FOUND;
import static io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationException.Reason.RESERVATION_NOT_FOUND;

/** Local transactions only; authorization to confirm or compensate belongs to Saga coordination. */
@Service
public class AssetReservationService {
    private static final Set<String> BUSINESS_UNIQUE_CONSTRAINTS = Set.of(
            "uk_asset_reservations_order", "uk_asset_reservations_saga", "uk_asset_reservations_operation"
    );

    private final PositionRepository positions;
    private final AssetReservationRepository reservations;
    private final String counterpartyId;

    public AssetReservationService(
            PositionRepository positions, AssetReservationRepository reservations,
            @Value("${custody.counterparty-id}") String counterpartyId
    ) {
        if (counterpartyId == null || counterpartyId.isBlank()) {
            throw new IllegalArgumentException("custody.counterparty-id is required");
        }
        this.positions = positions;
        this.reservations = reservations;
        this.counterpartyId = counterpartyId;
    }

    @Transactional(noRollbackFor = AssetReservationRefusal.class)
    public UUID reserve(ReserveAssetCommand command) {
        Objects.requireNonNull(command, "command is required");
        var existing = reservations.findByOperationId(command.operationId());
        if (existing.isPresent()) {
            return replay(existing.get(), command);
        }
        if (counterpartyId.equals(command.buyerId())) {
            throw new IllegalArgumentException("Buyer must differ from the counterparty");
        }

        var source = positions.findForUpdate(counterpartyId, command.assetId())
                .orElseThrow(() -> new AssetReservationRefusal(POSITION_NOT_FOUND,
                        "Counterparty position was not found"));

        // Recheck after the source lock: another transaction may have created the reservation.
        existing = reservations.findByOperationId(command.operationId());
        if (existing.isPresent()) {
            return replay(existing.get(), command);
        }
        if (reservations.existsByOrderIdOrSagaId(command.orderId(), command.sagaId())) {
            throw new AssetReservationException(OPERATION_CONFLICT,
                    "The order or saga already has an asset reservation");
        }
        if (positions.reserve(source.getId(), command.quantity()) != 1) {
            throw new AssetReservationRefusal(INSUFFICIENT_ASSETS,
                    "The counterparty does not have enough available assets");
        }

        var reservation = AssetReservation.reserve(command.orderId(), command.sagaId(),
                command.operationId(), source.getId(), command.buyerId(), command.assetId(), command.quantity());
        try {
            return reservations.saveAndFlush(reservation).getId();
        } catch (DataIntegrityViolationException error) {
            // Different assets have different locks. Their identifier collisions are resolved by the DB.
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && violation.getConstraintName() != null
                        && BUSINESS_UNIQUE_CONSTRAINTS.contains(violation.getConstraintName())) {
                    var conflict = new AssetReservationException(OPERATION_CONFLICT,
                            "The order, saga or operation already belongs to another reservation");
                    conflict.initCause(error);
                    throw conflict;
                }
            }
            throw error;
        }
    }

    @Transactional
    public void confirm(UUID reservationId) {
        var reservation = findAfterLockingSource(reservationId);
        if (reservation.getStatus() == AssetReservationStatus.CONFIRMED) {
            return;
        }
        requireReserved(reservation);
        requireUpdate(positions.consume(reservation.getSourcePositionId(), reservation.getQuantity()));
        requireUpdate(positions.credit(UUID.randomUUID(), reservation.getBuyerId(),
                reservation.getAssetId(), reservation.getQuantity()));
        reservation.confirm();
        reservations.saveAndFlush(reservation);
    }

    @Transactional
    public void release(UUID reservationId) {
        var reservation = findAfterLockingSource(reservationId);
        if (reservation.getStatus() == AssetReservationStatus.RELEASED) {
            return;
        }
        requireReserved(reservation);
        requireUpdate(positions.release(reservation.getSourcePositionId(), reservation.getQuantity()));
        reservation.release();
        reservations.saveAndFlush(reservation);
    }

    private UUID replay(AssetReservation reservation, ReserveAssetCommand command) {
        if (!reservation.matches(command.orderId(), command.sagaId(),
                command.buyerId(), command.assetId(), command.quantity())) {
            throw new AssetReservationException(OPERATION_CONFLICT,
                    "operationId was already used with different reservation data");
        }
        return reservation.getId();
    }

    private AssetReservation findAfterLockingSource(UUID reservationId) {
        Objects.requireNonNull(reservationId, "reservationId is required");
        UUID sourceId = reservations.findSourcePositionId(reservationId)
                .orElseThrow(() -> new AssetReservationException(RESERVATION_NOT_FOUND,
                        "Asset reservation was not found"));
        // Use the original source even if configuration changes after a restart.
        positions.findByIdForUpdate(sourceId)
                .orElseThrow(() -> new AssetReservationException(POSITION_NOT_FOUND,
                        "Original position was not found"));
        return reservations.findById(reservationId)
                .orElseThrow(() -> new AssetReservationException(RESERVATION_NOT_FOUND,
                        "Asset reservation was not found"));
    }

    private void requireReserved(AssetReservation reservation) {
        if (reservation.getStatus() != AssetReservationStatus.RESERVED) {
            throw new AssetReservationException(INVALID_TRANSITION,
                    "The reservation already reached an incompatible terminal state");
        }
    }

    private void requireUpdate(int changedRows) {
        if (changedRows != 1) {
            throw new IllegalStateException("Position is inconsistent with the stored asset reservation");
        }
    }
}
