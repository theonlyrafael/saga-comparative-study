package io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.repository;

import io.github.theonlyrafael.sagastudy.orchestrated.messaging.SagaEnvelope;
import io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.domain.SagaState;
import io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.domain.SagaStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class SagaRepository {
    private final JdbcTemplate jdbc;

    public SagaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void start(SagaEnvelope event) {
        int inserted = jdbc.update("""
                INSERT INTO saga_instances (saga_id, order_id, state, snapshot, order_received_at)
                VALUES (?, ?, 'RESERVANDO_SALDO', ?::jsonb, ?) ON CONFLICT DO NOTHING
                """, event.sagaId(), event.orderId(), event.snapshot().toString(), java.sql.Timestamp.from(event.occurredAt()));
        if (inserted != 1) throw new AmqpRejectAndDontRequeueException("Order or Saga already started");
    }

    /** Serialize replies only within the same execution, including the two independent confirmations. */
    public void lock(SagaEnvelope event) {
        var rows = jdbc.queryForList("""
                SELECT saga_id FROM saga_instances WHERE saga_id = ? AND order_id = ? FOR UPDATE
                """, UUID.class, event.sagaId(), event.orderId());
        if (rows.isEmpty()) throw new AmqpRejectAndDontRequeueException("Unknown Saga/order pair");
    }

    public void advance(UUID sagaId, SagaState state) {
        jdbc.update("UPDATE saga_instances SET state=?, updated_at=clock_timestamp() WHERE saga_id=?",
                state.name(), sagaId);
    }

    @Transactional(readOnly = true)
    public Optional<SagaStatus> find(UUID id) {
        return jdbc.query("SELECT saga_id, order_id, state FROM saga_instances WHERE saga_id=?",
                (rs, i) -> new SagaStatus(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        SagaState.valueOf(rs.getString(3))), id).stream().findFirst();
    }
}
