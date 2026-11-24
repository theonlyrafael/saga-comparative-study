package io.github.theonlyrafael.sagastudy.orchestrated.settlement.repository;

import io.github.theonlyrafael.sagastudy.orchestrated.settlement.application.ConfirmSettlementCommand;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.application.Settlement;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.ReservedAsset;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.ReservedBalance;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.SettlementConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** No update/delete operation: a committed decision is irreversible through this interface. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class SettlementRepository {
    private final JdbcTemplate jdbc;

    public SettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** An exact replay observes the first committed outcome, even after the simulator is restarted. */
    public boolean admit(ConfirmSettlementCommand command, boolean requestDenial) {
        var balance = command.balance();
        var assetId = command.asset().reservationId();
        jdbc.update("""
                INSERT INTO settlement_decisions
                    (order_id, saga_id, operation_id, balance_reservation_id, asset_reservation_id, outcome)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, balance.orderId(), balance.sagaId(), command.operationId(), balance.reservationId(),
                assetId, requestDenial ? "DENIED" : "CONFIRMED");
        return jdbc.query("""
                SELECT outcome FROM settlement_decisions WHERE order_id=? AND saga_id=? AND operation_id=?
                    AND balance_reservation_id=? AND asset_reservation_id=?
                """, (row, index) -> row.getString(1).equals("CONFIRMED"), balance.orderId(), balance.sagaId(),
                command.operationId(), balance.reservationId(), assetId).stream().findFirst()
                .orElseThrow(SettlementConflictException::new);
    }

    /** Unique indexes arbitrate simultaneous commands; no exception/retry transaction is needed for duplicates. */
    public void insertIfAbsent(ConfirmSettlementCommand command) {
        jdbc.update("""
                INSERT INTO settlements (settlement_id, operation_id, order_id, saga_id,
                    balance_reservation_id, asset_reservation_id, buyer_id, asset_id,
                    quantity, unit_price, amount, catalog_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, UUID.randomUUID(), command.operationId(), command.balance().orderId(), command.balance().sagaId(),
                command.balance().reservationId(), command.asset().reservationId(), command.balance().buyerId(),
                command.asset().assetId(), command.asset().quantity(), command.unitPrice(),
                command.balance().amount(), command.catalogVersion());
    }

    public Optional<Settlement> findByOperationId(UUID operationId) {
        return find("operation_id", operationId);
    }

    public Optional<Settlement> findByOrderId(UUID orderId) {
        return find("order_id", orderId);
    }

    public Optional<Settlement> findBySagaId(UUID sagaId) {
        return find("saga_id", sagaId);
    }

    // Column names are private constants selected above, never caller input.
    private Optional<Settlement> find(String column, UUID id) {
        Objects.requireNonNull(id, "lookup identifier is required");
        return jdbc.query("SELECT * FROM settlements WHERE " + column + " = ?",
                (row, index) -> map(row), id).stream().findFirst();
    }

    private Settlement map(ResultSet row) throws SQLException {
        UUID orderId = row.getObject("order_id", UUID.class);
        UUID sagaId = row.getObject("saga_id", UUID.class);
        String buyerId = row.getString("buyer_id");
        var balance = new ReservedBalance(row.getObject("balance_reservation_id", UUID.class),
                orderId, sagaId, buyerId, row.getBigDecimal("amount"));
        var asset = new ReservedAsset(row.getObject("asset_reservation_id", UUID.class),
                orderId, sagaId, buyerId, row.getString("asset_id"), row.getInt("quantity"));
        var command = new ConfirmSettlementCommand(row.getObject("operation_id", UUID.class),
                balance, asset, row.getBigDecimal("unit_price"), row.getString("catalog_version"));
        return new Settlement(row.getObject("settlement_id", UUID.class), command,
                row.getTimestamp("confirmed_at").toInstant());
    }
}
