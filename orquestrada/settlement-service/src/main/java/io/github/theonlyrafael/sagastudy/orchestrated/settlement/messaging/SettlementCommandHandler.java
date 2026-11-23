package io.github.theonlyrafael.sagastudy.orchestrated.settlement.messaging;

import io.github.theonlyrafael.sagastudy.orchestrated.settlement.application.ConfirmSettlementCommand;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.application.SettlementService;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.ReservedAsset;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.ReservedBalance;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.SettlementConflictException;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.SagaEnvelope;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.CommandReply;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.ParticipantHandler;
import java.math.BigDecimal;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Component;

@Component
public class SettlementCommandHandler implements ParticipantHandler {
    private final SettlementService settlements;

    public SettlementCommandHandler(SettlementService settlements) {
        this.settlements = settlements;
    }

    @Override
    public CommandReply handle(SagaEnvelope command) {
        if (!command.type().equals("ConfirmarLiquidacao")) {
            throw new AmqpRejectAndDontRequeueException("Unexpected settlement command");
        }
        var payload = command.payload();
        String buyer = payload.get("clienteId").asString();
        var balance = new ReservedBalance(command.resource("reservaSaldoId"), command.orderId(), command.sagaId(),
                buyer, new BigDecimal(payload.get("valorTotal").asString()));
        var asset = new ReservedAsset(command.resource("reservaAtivoId"), command.orderId(), command.sagaId(),
                buyer, payload.get("ativoId").asString(), payload.get("quantidade").asInt());
        var confirmation = new ConfirmSettlementCommand(command.operationId(), balance, asset,
                new BigDecimal(payload.get("precoUnitario").asString()), payload.get("versaoCatalogo").asString());
        try {
            var decision = settlements.confirm(confirmation);
            return CommandReply.of(command, "LiquidacaoConcluida").with("liquidacaoId", decision.id().toString());
        } catch (SettlementConflictException conflict) {
            // A conflicting durable decision is never authorization to compensate.
            throw new AmqpRejectAndDontRequeueException("Conflicting settlement requires reconciliation", conflict);
        }
    }
}
