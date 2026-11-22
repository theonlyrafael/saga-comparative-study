package io.github.theonlyrafael.sagastudy.orchestrated.messaging;

import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.node.ObjectNode;

@Component
@ConditionalOnProperty("inbox.queue")
public class ParticipantConsumer {
    private final JdbcTemplate jdbc;
    private final ObjectReader reader;
    private final ParticipantHandler handler;
    private final String consumer;

    public ParticipantConsumer(JdbcTemplate jdbc, ObjectMapper mapper, ParticipantHandler handler,
                               @Value("${inbox.queue}") String consumer) {
        this.jdbc = jdbc;
        this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.handler = handler;
        this.consumer = consumer;
    }

    @Transactional
    @RabbitListener(queues = "${inbox.queue}")
    public void receive(Message delivery) {
        CommandEnvelope command;
        ObjectNode request;
        try {
            var tree = reader.readTree(delivery.getBody());
            command = CommandEnvelope.parse(tree, delivery.getMessageProperties().getReceivedRoutingKey());
            var metadata = delivery.getMessageProperties();
            if ((metadata.getMessageId() != null && !metadata.getMessageId().equalsIgnoreCase(command.messageId().toString()))
                    || (metadata.getType() != null && !metadata.getType().equals(command.type()))) {
                throw new IllegalArgumentException("AMQP properties disagree with envelope");
            }
            request = (ObjectNode) tree;
            request.remove("messageId");
            request.remove("occurredAt");
        } catch (IllegalArgumentException | java.time.DateTimeException | JacksonException invalid) {
            throw new AmqpRejectAndDontRequeueException("Invalid v1 command", invalid);
        }

        int inserted = jdbc.update("""
                INSERT INTO inbox_messages (consumer_name, message_id, operation_id)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, consumer, command.messageId(), command.operationId());
        bindDelivery(command);
        if (inserted == 0) {
            replay(command, request);
            return;
        }

        var reply = handler.handle(command);
        var id = UUID.randomUUID();
        var envelope = request.deepCopy();
        envelope.put("messageId", id.toString());
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("type", reply.type());
        envelope.set("payload", reply.payload());
        jdbc.update("""
                INSERT INTO outbox_messages (message_id, consumer_name, operation_id, message_type, request, envelope)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, id, consumer, command.operationId(), reply.type(), request.toString(), envelope.toString());
    }

    private void bindDelivery(CommandEnvelope command) {
        int accepted = jdbc.update("""
                INSERT INTO inbox_deliveries (consumer_name, message_id, operation_id) VALUES (?, ?, ?)
                ON CONFLICT (consumer_name, message_id) DO UPDATE SET operation_id = EXCLUDED.operation_id
                WHERE inbox_deliveries.operation_id = EXCLUDED.operation_id
                """, consumer, command.messageId(), command.operationId());
        if (accepted == 0) {
            throw new AmqpRejectAndDontRequeueException("Message ID already belongs to another operation");
        }
    }

    private void replay(CommandEnvelope command, ObjectNode request) {
        int matched = jdbc.update("""
                UPDATE outbox_messages SET published_at = NULL, next_attempt_at = CURRENT_TIMESTAMP
                WHERE consumer_name = ? AND operation_id = ? AND request = ?::jsonb
                """, consumer, command.operationId(), request.toString());
        if (matched != 1) {
            throw new AmqpRejectAndDontRequeueException("Identifier reused with a different command");
        }
    }
}
