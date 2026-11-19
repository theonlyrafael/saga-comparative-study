package io.github.theonlyrafael.sagastudy.orchestrated.order.messaging;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxDeliveryStore store;
    private final OrderEventSender sender;
    private final long retryMillis;

    public OutboxDispatcher(OutboxDeliveryStore store, OrderEventSender sender,
                            @Value("${outbox.retry-delay-ms:5000}") long retryMillis) {
        if (retryMillis <= 0) throw new IllegalArgumentException("Positive retry delay required");
        this.store = store;
        this.sender = sender;
        this.retryMillis = retryMillis;
    }

    @Transactional
    public boolean publishNext() {
        var next = store.lockNext();
        if (next.isEmpty()) return false;
        var event = next.get();
        try {
            if (sender.send(event)) {
                store.markPublished(event.id());
                return true;
            }
            LOG.warn("Broker rejected or returned outbox message {}", event.id());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during publisher confirm", interrupted);
        } catch (AmqpException | ExecutionException | TimeoutException failure) {
            LOG.warn("Delivery of outbox message {} pending: {}", event.id(), failure.toString());
        }
        store.defer(event.id(), retryMillis);
        return false;
    }
}
