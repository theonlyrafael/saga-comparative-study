package io.github.theonlyrafael.sagastudy.orchestrated.order.repository;

import io.github.theonlyrafael.sagastudy.orchestrated.order.messaging.OrderReceived;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.ReplyOutbox;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.SagaEnvelope;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.SagaTracing;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class OutboxRepository implements ReplyOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OutboxRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void append(String consumer, SagaEnvelope command, UUID messageId, ObjectNode reply) {
        jdbc.update("""
                INSERT INTO outbox_messages (message_id, order_id, consumer_name, operation_id, message_type, request, envelope, traceparent)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """, messageId, command.orderId(), consumer, command.operationId(), reply.get("type").asString(),
                command.content().toString(), reply.toString(), SagaTracing.currentParent());
    }

    public void append(OrderReceived event) {
        jdbc.update("""
                INSERT INTO outbox_messages (message_id, order_id, message_type, envelope, traceparent)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?)
                """, event.messageId(), event.orderId(), event.type(), json.writeValueAsString(event), SagaTracing.currentParent());
    }
}
