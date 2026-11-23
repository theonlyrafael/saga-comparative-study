package io.github.theonlyrafael.sagastudy.orchestrated.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Content excludes the two delivery-specific fields so retries can be compared by operation. */
public record SagaEnvelope(String type, UUID messageId, UUID operationId, UUID orderId, UUID sagaId, ObjectNode content) {
    private static final Set<String> HEADER = Set.of("type", "schemaVersion", "messageId", "operationId",
            "orderId", "sagaId", "occurredAt", "payload");
    private static final Set<String> SNAPSHOT = Set.of("clienteId", "ativoId", "quantidade",
            "precoUnitario", "valorTotal", "versaoCatalogo");

    public static SagaEnvelope parse(JsonNode node, String route) {
        require(node instanceof ObjectNode && node.propertyNames().equals(HEADER), "Invalid envelope fields");
        require(node.get("schemaVersion").isIntegralNumber() && node.get("schemaVersion").asString().equals("1"),
                "Unsupported schema version");
        String type = text(node, "type", 64);
        require(type.equals(route), "Routing key must match type");
        Instant.parse(text(node, "occurredAt", 64));
        require(node.get("payload") instanceof ObjectNode, "Object payload required");
        validatePayload(type, (ObjectNode) node.get("payload"));
        var content = ((ObjectNode) node).deepCopy();
        content.remove("messageId");
        content.remove("occurredAt");
        return new SagaEnvelope(type, uuid(node, "messageId"), uuid(node, "operationId"),
                uuid(node, "orderId"), uuid(node, "sagaId"), content);
    }

    private static void validatePayload(String type, ObjectNode payload) {
        Set<String> extras = switch (type) {
            case "OrdemRecebida", "ReservarSaldo" -> Set.of();
            case "ReservarAtivo", "DesfazerReservaSaldo", "SaldoReservado", "ReservaSaldoDesfeita" ->
                    Set.of("reservaSaldoId");
            case "DesfazerReservaAtivo", "ReservaAtivoDesfeita" -> Set.of("reservaAtivoId");
            case "ConfirmarReservaSaldo", "SaldoConfirmado" -> Set.of("reservaSaldoId", "liquidacaoId");
            case "ConfirmarReservaAtivo", "AtivoConfirmado" -> Set.of("reservaAtivoId", "liquidacaoId");
            case "ConfirmarLiquidacao", "AtivoReservado" -> Set.of("reservaSaldoId", "reservaAtivoId");
            case "LiquidacaoConcluida" -> Set.of("reservaSaldoId", "reservaAtivoId", "liquidacaoId");
            case "OrdemLiquidada" -> Set.of("liquidacaoId");
            case "SaldoRecusado", "OrdemRejeitada" -> Set.of("motivo");
            case "AtivoRecusado" -> Set.of("reservaSaldoId", "motivo");
            case "LiquidacaoFalhou" -> Set.of("reservaSaldoId", "reservaAtivoId", "motivo");
            case "FinalizarOrdem" -> switch (text(payload, "estado", 16)) {
                case "LIQUIDADA" -> Set.of("estado", "liquidacaoId");
                case "REJEITADA" -> Set.of("estado", "motivo");
                default -> throw new IllegalArgumentException("Invalid final order status");
            };
            default -> throw new IllegalArgumentException("Unknown v1 message type");
        };
        var fields = new HashSet<>(SNAPSHOT);
        fields.addAll(extras);
        require(payload.propertyNames().equals(fields), "Invalid payload fields");
        for (String field : extras) {
            if (field.endsWith("Id")) uuid(payload, field);
        }
        if (extras.contains("motivo")) {
            var reason = payload.get("motivo");
            require(reason.isString() && reason.asString().matches("[A-Z][A-Z0-9_]+"), "Invalid reason");
        }
        text(payload, "clienteId", 64);
        text(payload, "ativoId", 128);
        text(payload, "versaoCatalogo", 128);
        var quantity = payload.get("quantidade");
        require(quantity.isIntegralNumber() && quantity.canConvertToInt() && quantity.asInt() > 0, "Invalid quantity");
        var price = money(payload, "precoUnitario");
        var total = money(payload, "valorTotal");
        require(price.multiply(BigDecimal.valueOf(quantity.asInt())).compareTo(total) == 0, "Inconsistent order value");
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

    public ObjectNode payload() {
        return (ObjectNode) content.get("payload");
    }

    public ObjectNode snapshot() {
        return payload().deepCopy().retain(SNAPSHOT);
    }

    public UUID resource(String field) {
        return uuid(payload(), field);
    }

    public ObjectNode followUp(String nextType, UUID operation, UUID delivery, ObjectNode payload) {
        var next = content.deepCopy();
        next.put("type", nextType);
        next.put("operationId", operation.toString());
        next.put("messageId", delivery.toString());
        next.put("occurredAt", Instant.now().toString());
        next.set("payload", payload);
        return next;
    }
}
