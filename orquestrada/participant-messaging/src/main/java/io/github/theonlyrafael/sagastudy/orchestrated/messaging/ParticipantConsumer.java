package io.github.theonlyrafael.sagastudy.orchestrated.messaging;

import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty("inbox.queue")
public class ParticipantConsumer {
    private final JdbcTemplate jdbc;
    private final SagaTracing tracing;
    private final EnvelopeReader reader;
    private final ParticipantHandler handler;
    private final ReplyOutbox outbox;
    private final String consumer;

    public ParticipantConsumer(JdbcTemplate jdbc, SagaTracing tracing, EnvelopeReader reader, ParticipantHandler handler,
                               ReplyOutbox outbox, @Value("${inbox.queue}") String consumer) {
        this.jdbc = jdbc;
        this.tracing = tracing;
        this.reader = reader;
        this.handler = handler;
        this.outbox = outbox;
        this.consumer = consumer;
    }

    @Transactional
    @RabbitListener(queues = "${inbox.queue}")
    public void receive(Message delivery) {
        var command = reader.read(delivery);
        tracing.consume(delivery, command, () -> process(command));
    }

    private void process(SagaEnvelope command) {
        int inserted = jdbc.update("""
                INSERT INTO inbox_messages (consumer_name, message_id, operation_id)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, consumer, command.messageId(), command.operationId());
        bindDelivery(command);
        if (inserted == 0) {
            replay(command);
            return;
        }

        var reply = handler.handle(command);
        var id = UUID.randomUUID();
        var envelope = command.followUp(reply.type(), command.operationId(), id, reply.payload());
        outbox.append(consumer, command, id, envelope);
    }

    private void bindDelivery(SagaEnvelope command) {
        int accepted = jdbc.update("""
                INSERT INTO inbox_deliveries (consumer_name, message_id, operation_id) VALUES (?, ?, ?)
                ON CONFLICT (consumer_name, message_id) DO UPDATE SET operation_id = EXCLUDED.operation_id
                WHERE inbox_deliveries.operation_id = EXCLUDED.operation_id
                """, consumer, command.messageId(), command.operationId());
        if (accepted == 0) {
            throw new AmqpRejectAndDontRequeueException("Message ID already belongs to another operation");
        }
    }

    private void replay(SagaEnvelope command) {
        int matched = jdbc.update("""
                UPDATE outbox_messages SET published_at = NULL, next_attempt_at = CURRENT_TIMESTAMP
                WHERE consumer_name = ? AND operation_id = ? AND request = ?::jsonb
                """, consumer, command.operationId(), command.content().toString());
        if (matched != 1) {
            throw new AmqpRejectAndDontRequeueException("Identifier reused with a different command");
        }
    }
}
