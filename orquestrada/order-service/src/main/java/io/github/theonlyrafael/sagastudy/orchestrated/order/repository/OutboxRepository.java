package io.github.theonlyrafael.sagastudy.orchestrated.order.repository;

import io.github.theonlyrafael.sagastudy.orchestrated.order.messaging.OrderReceived;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OutboxRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OrderReceived event) {
        jdbc.update("""
                INSERT INTO outbox_messages (message_id, order_id, message_type, envelope)
                VALUES (?, ?, ?, CAST(? AS jsonb))
                """, event.messageId(), event.orderId(), event.type(), json.writeValueAsString(event));
    }
}
