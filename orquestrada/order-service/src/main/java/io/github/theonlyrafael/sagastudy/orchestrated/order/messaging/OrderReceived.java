package io.github.theonlyrafael.sagastudy.orchestrated.order.messaging;

import io.github.theonlyrafael.sagastudy.orchestrated.order.domain.Order;
import java.util.UUID;

public record OrderReceived(String type, int schemaVersion, UUID messageId, UUID orderId,
                           UUID sagaId, UUID operationId, String occurredAt, OrderReceivedPayload payload) {
    public static OrderReceived from(Order order) {
        return new OrderReceived("OrdemRecebida", 1, UUID.randomUUID(), order.orderId(), order.sagaId(),
                UUID.randomUUID(), order.criadaEm().toString(), OrderReceivedPayload.from(order));
    }
}
