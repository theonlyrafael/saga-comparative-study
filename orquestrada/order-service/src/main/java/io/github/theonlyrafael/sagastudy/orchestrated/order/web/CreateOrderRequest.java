package io.github.theonlyrafael.sagastudy.orchestrated.order.web;

import jakarta.validation.constraints.*;

public record CreateOrderRequest(@NotBlank @Size(max = 64) String clienteId,
                                 @NotBlank @Size(max = 128) String ativoId,
                                 @NotNull @Positive Integer quantidade) {}
