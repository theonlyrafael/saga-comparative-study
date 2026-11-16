package io.github.theonlyrafael.sagastudy.orchestrated.order.application;


public record CreateOrderCommand(String clienteId, String ativoId, int quantidade, String key) {
    public CreateOrderCommand {
        identifier(clienteId, 64, "clienteId");
        identifier(ativoId, 128, "ativoId");
        identifier(key, 128, "Idempotency-Key");
        if (quantidade <= 0) throw new IllegalArgumentException("quantidade must be positive");
    }

    private static void identifier(String value, int max, String field) {
        if (value == null
                || value.isBlank()
                || value.length() > max
                || !value.equals(value.strip()))
            throw new IllegalArgumentException(field + " is invalid");
    }
}
