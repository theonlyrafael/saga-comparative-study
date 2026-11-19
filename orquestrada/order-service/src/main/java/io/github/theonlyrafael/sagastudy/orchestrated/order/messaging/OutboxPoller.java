package io.github.theonlyrafael.sagastudy.orchestrated.order.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {
    private final OutboxDispatcher dispatcher;

    public OutboxPoller(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval:1s}")
    public void drain() {
        for (int processed = 0; processed < 20; processed++) {
            if (!dispatcher.publishNext()) return;
        }
    }
}
