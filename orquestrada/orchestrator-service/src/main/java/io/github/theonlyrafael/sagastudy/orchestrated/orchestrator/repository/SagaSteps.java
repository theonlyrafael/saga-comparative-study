package io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.repository;

import io.github.theonlyrafael.sagastudy.orchestrated.messaging.SagaEnvelope;
import io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.domain.SagaStep;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class SagaSteps {
    private final JdbcTemplate jdbc;

    public SagaSteps(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void issue(SagaEnvelope cause, SagaStep step, ObjectNode payload) {
        var operationId = UUID.randomUUID();
        var messageId = UUID.randomUUID();
        var envelope = cause.followUp(step.commandType(), operationId, messageId, payload);
        jdbc.update("INSERT INTO saga_steps (operation_id, saga_id, command_type) VALUES (?, ?, ?)",
                operationId, cause.sagaId(), step.commandType());
        jdbc.update("""
                INSERT INTO outbox_messages (message_id, operation_id, message_type, envelope) VALUES (?, ?, ?, ?::jsonb)
                """, messageId, operationId, step.commandType(), envelope.toString());
    }

    /** Accept only a reply to the pending command, with every issued resource and snapshot value intact. */
    public void complete(SagaEnvelope reply, SagaStep step) {
        var payload = reply.payload().deepCopy();
        if (step == SagaStep.FINALIZE_ORDER) {
            payload.put("estado", reply.type().equals("OrdemLiquidada") ? "LIQUIDADA" : "REJEITADA");
        }
        int updated = jdbc.update("""
                UPDATE saga_steps s SET response_type=?, completed_at=clock_timestamp()
                FROM outbox_messages o
                WHERE s.operation_id=? AND s.saga_id=? AND s.command_type=? AND s.completed_at IS NULL
                    AND o.operation_id=s.operation_id AND (o.envelope->'payload') <@ ?::jsonb
                """, reply.type(), reply.operationId(), reply.sagaId(), step.commandType(), payload.toString());
        if (updated != 1) throw new AmqpRejectAndDontRequeueException("Reply does not match the issued command");
    }

    public boolean bothEffectsConfirmed(UUID sagaId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT count(*) = 2 FROM saga_steps WHERE saga_id = ?
                    AND command_type IN ('ConfirmarReservaSaldo', 'ConfirmarReservaAtivo')
                    AND completed_at IS NOT NULL
                """, Boolean.class, sagaId));
    }
}
