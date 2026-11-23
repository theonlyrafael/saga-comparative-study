package io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.repository;

import io.github.theonlyrafael.sagastudy.orchestrated.messaging.SagaEnvelope;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class SagaInbox {
    private final JdbcTemplate jdbc;

    public SagaInbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One reply per operation, even when the broker or participant uses a new delivery ID. */
    public boolean claim(SagaEnvelope event) {
        var content = event.content().toString();
        int inserted = jdbc.update("""
                INSERT INTO inbox_operations (operation_id, saga_id, content) VALUES (?, ?, ?::jsonb)
                ON CONFLICT (operation_id) DO NOTHING
                """, event.operationId(), event.sagaId(), content);
        if (inserted == 0 && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM inbox_operations WHERE operation_id=? AND content=?::jsonb)
                """, Boolean.class, event.operationId(), content))) {
            throw new AmqpRejectAndDontRequeueException("Reply differs from the recorded operation");
        }
        int bound = jdbc.update("""
                INSERT INTO inbox_deliveries (message_id, operation_id) VALUES (?, ?)
                ON CONFLICT (message_id) DO UPDATE SET operation_id = EXCLUDED.operation_id
                WHERE inbox_deliveries.operation_id = EXCLUDED.operation_id
                """, event.messageId(), event.operationId());
        if (bound != 1) throw new AmqpRejectAndDontRequeueException("Delivery ID reused for another operation");
        return inserted == 1;
    }
}
