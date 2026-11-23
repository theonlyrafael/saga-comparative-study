package io.github.theonlyrafael.sagastudy.orchestrated.order.messaging;

import io.github.theonlyrafael.sagastudy.orchestrated.messaging.CommandReply;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.ParticipantHandler;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.SagaEnvelope;
import io.github.theonlyrafael.sagastudy.orchestrated.order.domain.OrderStatus;
import io.github.theonlyrafael.sagastudy.orchestrated.order.repository.OrderRepository;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderFinalizationHandler implements ParticipantHandler {
    private final OrderRepository orders;
    private final ObjectMapper json;

    public OrderFinalizationHandler(OrderRepository orders, ObjectMapper json) {
        this.orders = orders;
        this.json = json;
    }

    @Override
    public CommandReply handle(SagaEnvelope command) {
        if (!command.type().equals("FinalizarOrdem")) {
            throw new AmqpRejectAndDontRequeueException("Expected FinalizarOrdem");
        }
        var order = orders.find(command.orderId())
                .orElseThrow(() -> new AmqpRejectAndDontRequeueException("Order not found"));
        if (!order.sagaId().equals(command.sagaId())
                || !command.snapshot().equals(json.valueToTree(OrderReceivedPayload.from(order)))) {
            throw new AmqpRejectAndDontRequeueException("Order and finalization command do not match");
        }
        var payload = command.payload();
        var outcome = OrderStatus.valueOf(payload.get("estado").asString());
        boolean settled = outcome == OrderStatus.LIQUIDADA;
        UUID settlementId = settled ? command.resource("liquidacaoId") : null;
        String reason = settled ? null : payload.get("motivo").asString();
        if (!orders.finish(order.orderId(), outcome, settlementId, reason)) {
            throw new AmqpRejectAndDontRequeueException("Order already finalized by another operation");
        }
        var response = payload.deepCopy();
        response.remove("estado");
        return new CommandReply(settled ? "OrdemLiquidada" : "OrdemRejeitada", response);
    }
}
