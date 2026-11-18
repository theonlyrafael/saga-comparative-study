package io.github.theonlyrafael.sagastudy.orchestrated.order.messaging;

import io.github.theonlyrafael.sagastudy.orchestrated.order.domain.Order;

public record OrderReceivedPayload(String clienteId, String ativoId, int quantidade,
                                  String precoUnitario, String valorTotal, String versaoCatalogo) {
    public static OrderReceivedPayload from(Order order) {
        return new OrderReceivedPayload(order.clienteId(), order.ativoId(), order.quantidade(),
                order.precoUnitario().toPlainString(), order.valorTotal().toPlainString(), order.versaoCatalogo());
    }
}
