package io.github.theonlyrafael.sagastudy.orchestrated.order.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Order(
        UUID orderId,
        UUID sagaId,
        String clienteId,
        String ativoId,
        int quantidade,
        BigDecimal precoUnitario,
        BigDecimal valorTotal,
        String versaoCatalogo,
        OrderStatus estado,
        Instant criadaEm,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID liquidacaoId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String motivo) {

    /** The idempotent POST returns the original receipt even after GET exposes a final outcome. */
    public Order receipt() {
        return new Order(orderId, sagaId, clienteId, ativoId, quantidade, precoUnitario, valorTotal,
                versaoCatalogo, OrderStatus.RECEBIDA, criadaEm, null, null);
    }
}
