package io.github.theonlyrafael.sagastudy.orchestrated.account.repository;

import io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservation;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface BalanceReservationRepository
        extends Repository<BalanceReservation, UUID> {

    Optional<BalanceReservation> findById(UUID id);

    Optional<BalanceReservation> findByOperationId(UUID operationId);

    boolean existsByOrderIdOrSagaId(UUID orderId, UUID sagaId);

    BalanceReservation saveAndFlush(BalanceReservation reservation);

    @Query("""
            select reservation.accountId
              from BalanceReservation reservation
             where reservation.id = :reservationId
            """)
    Optional<String> findAccountId(
            @Param("reservationId") UUID reservationId
    );
}
