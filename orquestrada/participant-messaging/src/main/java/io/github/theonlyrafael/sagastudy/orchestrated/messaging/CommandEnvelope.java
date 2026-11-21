package io.github.theonlyrafael.sagastudy.orchestrated.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public record CommandEnvelope(String type, UUID messageId, UUID operationId, UUID orderId,
                              UUID sagaId, ObjectNode payload) {
    private static final Set<String> HEADER = Set.of("type", "schemaVersion", "messageId", "operationId",
            "orderId", "sagaId", "occurredAt", "payload");
    private static final Set<String> SNAPSHOT = Set.of("clienteId", "ativoId", "quantidade",
            "precoUnitario", "valorTotal", "versaoCatalogo");

    public static CommandEnvelope parse(JsonNode node, String route) {
        require(node != null && node.isObject() && node.propertyNames().equals(HEADER), "Invalid envelope fields");
        require(node.get("schemaVersion").isIntegralNumber() && node.get("schemaVersion").asString().equals("1"),
                "Unsupported schema version");
        String type = text(node, "type", 64);
        require(type.equals(route), "Routing key must match type");
        Instant.parse(text(node, "occurredAt", 64));
        require(node.get("payload") instanceof ObjectNode, "Object payload required");
        var payload = (ObjectNode) node.get("payload");
        var fields = new HashSet<>(SNAPSHOT);
        var ids = switch (type) {
            case "ReservarSaldo" -> Set.<String>of();
            case "ReservarAtivo", "DesfazerReservaSaldo" -> Set.of("reservaSaldoId");
            case "DesfazerReservaAtivo" -> Set.of("reservaAtivoId");
            case "ConfirmarReservaSaldo" -> Set.of("reservaSaldoId", "liquidacaoId");
            case "ConfirmarReservaAtivo" -> Set.of("reservaAtivoId", "liquidacaoId");
            case "ConfirmarLiquidacao" -> Set.of("reservaSaldoId", "reservaAtivoId");
            default -> throw new IllegalArgumentException("Unknown participant command");
        };
        fields.addAll(ids);
        require(payload.propertyNames().equals(fields), "Invalid payload fields");
        ids.forEach(field -> uuid(payload, field));
        text(payload, "clienteId", 64);
        text(payload, "ativoId", 128);
        text(payload, "versaoCatalogo", 128);
        var quantity = payload.get("quantidade");
        require(quantity.isIntegralNumber() && quantity.canConvertToInt() && quantity.asInt() > 0, "Invalid quantity");
        var price = money(payload, "precoUnitario");
        var total = money(payload, "valorTotal");
        require(price.multiply(BigDecimal.valueOf(quantity.asInt())).compareTo(total) == 0, "Inconsistent order value");
        return new CommandEnvelope(type, uuid(node, "messageId"), uuid(node, "operationId"),
                uuid(node, "orderId"), uuid(node, "sagaId"), payload);
    }

    private static BigDecimal money(JsonNode node, String field) {
        String value = text(node, field, 20);
        require(value.matches("(0|[1-9][0-9]{0,16})[.][0-9]{2}") && !value.equals("0.00"), field);
        return new BigDecimal(value);
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field, 36);
        UUID id = UUID.fromString(value);
        require(id.toString().equalsIgnoreCase(value), field);
        return id;
    }

    private static String text(JsonNode node, String field, int maxLength) {
        var value = node.get(field);
        require(value != null && value.isString() && !value.asString().isBlank()
                && value.asString().length() <= maxLength, field);
        return value.asString();
    }

    private static void require(boolean valid, String field) {
        if (!valid) throw new IllegalArgumentException(field);
    }

    public UUID resource(String field) {
        return uuid(payload, field);
    }
}
