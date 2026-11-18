package io.github.theonlyrafael.sagastudy.orchestrated.order;

import io.github.theonlyrafael.sagastudy.orchestrated.order.domain.*;
import io.github.theonlyrafael.sagastudy.orchestrated.order.messaging.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OrderReceivedTest {
    private final Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "C", "A", 3,
            new BigDecimal("0.10"), new BigDecimal("0.30"), "v1", OrderStatus.RECEBIDA, Instant.now());

    @Test
    void capturesDecimalStringsAndCatalogVersion() {
        assertThat(OrderReceivedPayload.from(order))
                .isEqualTo(new OrderReceivedPayload("C", "A", 3, "0.10", "0.30", "v1"));
    }

    @Test
    void correlatesEventWithOriginalOrder() {
        var event = OrderReceived.from(order);
        assertThat(event.type()).isEqualTo("OrdemRecebida");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.orderId()).isEqualTo(order.orderId());
        assertThat(event.sagaId()).isEqualTo(order.sagaId());
        assertThat(event.occurredAt()).isEqualTo(order.criadaEm().toString());
        assertThat(event.operationId()).isNotNull().isNotEqualTo(event.messageId());
    }
}
