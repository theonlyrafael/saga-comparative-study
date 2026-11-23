package io.github.theonlyrafael.sagastudy.orchestrated.messaging;

import java.util.UUID;
import org.springframework.context.annotation.Fallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.node.ObjectNode;

@Repository
@Fallback
public class ParticipantReplyOutbox implements ReplyOutbox {
    private final JdbcTemplate jdbc;

    public ParticipantReplyOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(String consumer, SagaEnvelope command, UUID messageId, ObjectNode reply) {
        jdbc.update("""
                INSERT INTO outbox_messages (message_id, consumer_name, operation_id, message_type, request, envelope)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, messageId, consumer, command.operationId(), reply.get("type").asString(),
                command.content().toString(), reply.toString());
    }
}
