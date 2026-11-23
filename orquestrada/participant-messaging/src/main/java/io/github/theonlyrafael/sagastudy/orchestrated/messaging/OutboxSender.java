package io.github.theonlyrafael.sagastudy.orchestrated.messaging;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OutboxSender {
    private final RabbitTemplate rabbit;
    private final long timeoutMillis;
    private final String exchange;

    public OutboxSender(RabbitTemplate rabbit, @Value("${outbox.confirm-timeout-ms:5000}") long timeoutMillis,
                        @Value("${outbox.exchange:saga.v1.responses}") String exchange) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("Positive confirm timeout required");
        this.rabbit = rabbit;
        this.exchange = exchange;
        this.timeoutMillis = timeoutMillis;
    }

    public boolean send(PendingMessage event) throws InterruptedException, ExecutionException, TimeoutException {
        var properties = new MessageProperties();
        properties.setMessageId(event.id().toString());
        properties.setType(event.type());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var message = new Message(event.envelope().getBytes(StandardCharsets.UTF_8), properties);
        // Fresh correlation per attempt; the business envelope and AMQP messageId stay stable.
        var attempt = new CorrelationData();
        rabbit.send(exchange, event.type(), message, attempt);
        var confirm = attempt.getFuture().get(timeoutMillis, TimeUnit.MILLISECONDS);
        return confirm.ack() && attempt.getReturned() == null;
    }
}
