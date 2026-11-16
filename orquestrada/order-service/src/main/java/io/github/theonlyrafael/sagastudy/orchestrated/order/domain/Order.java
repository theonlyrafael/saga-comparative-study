package io.github.theonlyrafael.sagastudy.orchestrated.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable persisted snapshot; creation does not reserve or settle resources. */
public record Order(UUID orderId, UUID sagaId, String clienteId, String ativoId, int quantidade,
                    BigDecimal precoUnitario, BigDecimal valorTotal, String versaoCatalogo,
                    OrderStatus estado, Instant criadaEm) {}
