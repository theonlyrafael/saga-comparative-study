package io.github.theonlyrafael.sagastudy.orchestrated.custody.repository;

import io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservation;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AssetReservationRepository extends Repository<AssetReservation, UUID> {
    Optional<AssetReservation> findById(UUID id);
    Optional<AssetReservation> findByOperationId(UUID operationId);
    boolean existsByOrderIdOrSagaId(UUID orderId, UUID sagaId);
    AssetReservation saveAndFlush(AssetReservation reservation);

    @Query("select reservation.sourcePositionId from AssetReservation reservation where reservation.id = :reservationId")
    Optional<UUID> findSourcePositionId(@Param("reservationId") UUID reservationId);
}
