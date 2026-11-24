package io.github.theonlyrafael.sagastudy.orchestrated.settlement.messaging;

import io.github.theonlyrafael.sagastudy.orchestrated.messaging.CommandReply;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.ParticipantHandler;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.SagaEnvelope;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.application.ConfirmSettlementCommand;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.application.SettlementService;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.ReservedAsset;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.ReservedBalance;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.SettlementConflictException;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.SettlementDeniedException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SettlementCommandHandler implements ParticipantHandler {
    private final SettlementService settlements;
    private final Optional<UUID> simulatedDenialOrder;

    public SettlementCommandHandler(SettlementService settlements,
            @Value("${settlement.simulation.deny-order-id:}") Optional<UUID> simulatedDenialOrder) {
        this.simulatedDenialOrder = simulatedDenialOrder;
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
            boolean requestDenial = simulatedDenialOrder.filter(command.orderId()::equals).isPresent();
            var decision = settlements.decide(confirmation, requestDenial);
            return CommandReply.of(command, "LiquidacaoConcluida").with("liquidacaoId", decision.id().toString());
        } catch (SettlementDeniedException denied) {
            return CommandReply.of(command, "LiquidacaoFalhou").with("motivo", "LIQUIDACAO_NEGADA");
        } catch (SettlementConflictException conflict) {
            // A conflicting durable decision is never authorization to compensate.
            throw new AmqpRejectAndDontRequeueException("Conflicting settlement requires reconciliation", conflict);
        }
    }
}
