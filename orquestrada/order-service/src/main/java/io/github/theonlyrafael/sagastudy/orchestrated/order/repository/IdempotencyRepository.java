package io.github.theonlyrafael.sagastudy.orchestrated.order.repository;

import io.github.theonlyrafael.sagastudy.orchestrated.order.application.CreateOrderCommand;
import io.github.theonlyrafael.sagastudy.orchestrated.order.domain.IdempotencyConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class IdempotencyRepository {
    private final JdbcTemplate jdbc;
    public IdempotencyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public UUID claim(CreateOrderCommand command, UUID candidate) {
        jdbc.update("""
                INSERT INTO http_idempotency VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (cliente_id, idempotency_key) DO NOTHING
                """, command.clienteId(), command.key(), command.ativoId(), command.quantidade(), candidate);
        // Separate statement sees a concurrent winner after its transaction commits.
        return jdbc.queryForObject("""
                SELECT order_id, ativo_id, quantidade FROM http_idempotency
                WHERE cliente_id = ? AND idempotency_key = ?
                """, (row, n) -> {
                    if (!row.getString("ativo_id").equals(command.ativoId()) || row.getInt("quantidade") != command.quantidade())
                        throw new IdempotencyConflictException();
                    return row.getObject("order_id", UUID.class);
                }, command.clienteId(), command.key());
    }
}
