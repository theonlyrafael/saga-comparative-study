package io.github.theonlyrafael.sagastudy.orchestrated.custody.repository;

import io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.Position;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Stock primitives that require a caller-owned transaction. The reservation service
 * must coordinate these updates with reservation state to provide idempotency.
 * Consuming the counterparty's reservation and crediting the buyer belong to the
 * same transaction. A zero update count must be handled by that service.
 */
@Transactional(propagation = Propagation.MANDATORY)
public interface PositionRepository extends Repository<Position, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select position from Position position where position.id = :positionId")
    Optional<Position> findByIdForUpdate(@Param("positionId") UUID positionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select position from Position position where position.ownerId = :ownerId and position.assetId = :assetId")
    Optional<Position> findForUpdate(
            @Param("ownerId") String ownerId,
            @Param("assetId") String assetId
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE positions
               SET available_quantity = available_quantity - :quantity,
                   reserved_quantity = reserved_quantity + :quantity
             WHERE position_id = :positionId
               AND :quantity > 0
               AND available_quantity >= :quantity
            """, nativeQuery = true)
    int reserve(@Param("positionId") UUID positionId, @Param("quantity") long quantity);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE positions
               SET reserved_quantity = reserved_quantity - :quantity,
                   available_quantity = available_quantity + :quantity
             WHERE position_id = :positionId
               AND :quantity > 0
               AND reserved_quantity >= :quantity
            """, nativeQuery = true)
    int release(@Param("positionId") UUID positionId, @Param("quantity") long quantity);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE positions
               SET reserved_quantity = reserved_quantity - :quantity
             WHERE position_id = :positionId
               AND :quantity > 0
               AND reserved_quantity >= :quantity
            """, nativeQuery = true)
    int consume(@Param("positionId") UUID positionId, @Param("quantity") long quantity);

    /** The supplied ID is used only if the buyer's position does not exist yet. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO positions (position_id, owner_id, asset_id, available_quantity, reserved_quantity)
            SELECT :newPositionId, :ownerId, :assetId, :quantity, 0
             WHERE :quantity > 0
            ON CONFLICT (owner_id, asset_id) DO UPDATE
               SET available_quantity = positions.available_quantity + EXCLUDED.available_quantity
            """, nativeQuery = true)
    int credit(
            @Param("newPositionId") UUID newPositionId,
            @Param("ownerId") String ownerId,
            @Param("assetId") String assetId,
            @Param("quantity") long quantity
    );
}
