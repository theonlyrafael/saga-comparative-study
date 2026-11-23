package io.github.theonlyrafael.sagastudy.orchestrated.messaging;

import java.time.DateTimeException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/** Validates the wire contract before a listener changes any durable state. */
@Component
public class EnvelopeReader {
    private final ObjectReader reader;

    public EnvelopeReader(ObjectMapper mapper) {
        reader = mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public SagaEnvelope read(Message delivery) {
        try {
            var headers = delivery.getMessageProperties();
            var event = SagaEnvelope.parse(reader.readTree(delivery.getBody()), headers.getReceivedRoutingKey());
            if ((headers.getMessageId() != null && !headers.getMessageId().equalsIgnoreCase(event.messageId().toString()))
                    || (headers.getType() != null && !headers.getType().equals(event.type()))) {
                throw new IllegalArgumentException("AMQP properties disagree with envelope");
            }
            return event;
        } catch (IllegalArgumentException | DateTimeException | JacksonException invalid) {
            throw new AmqpRejectAndDontRequeueException("Invalid v1 message", invalid);
        }
    }
}
