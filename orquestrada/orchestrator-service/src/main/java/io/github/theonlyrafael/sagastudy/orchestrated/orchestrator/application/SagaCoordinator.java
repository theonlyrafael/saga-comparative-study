package io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.application;

import io.github.theonlyrafael.sagastudy.orchestrated.messaging.EnvelopeReader;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.SagaEnvelope;
import io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.repository.SagaInbox;
import io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.repository.SagaRepository;
import io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.repository.SagaSteps;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.domain.SagaState.*;
import static io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.domain.SagaStep.*;

@Service
public class SagaCoordinator {
    private final EnvelopeReader reader;
    private final SagaInbox inbox;
    private final SagaRepository sagas;
    private final SagaSteps steps;

    public SagaCoordinator(EnvelopeReader reader, SagaInbox inbox, SagaRepository sagas, SagaSteps steps) {
        this.reader = reader;
        this.inbox = inbox;
        this.sagas = sagas;
        this.steps = steps;
    }

    @RabbitListener(queues = "saga.v1.orchestrator")
    @Transactional
    public void receive(Message delivery) {
        var event = reader.read(delivery);
        if (!inbox.claim(event)) return;
        if (event.type().equals("OrdemRecebida")) {
            sagas.start(event);
            steps.issue(event, RESERVE_BALANCE, event.payload());
            return;
        }

        sagas.lock(event);
        switch (event.type()) {
            case "SaldoReservado" -> {
                steps.complete(event, RESERVE_BALANCE);
                steps.issue(event, RESERVE_ASSET, event.payload());
                sagas.advance(event.sagaId(), RESERVANDO_ATIVO);
            }
            case "AtivoReservado" -> {
                steps.complete(event, RESERVE_ASSET);
                steps.issue(event, SETTLE, event.payload());
                sagas.advance(event.sagaId(), CONFIRMANDO_LIQUIDACAO);
            }
            case "LiquidacaoConcluida" -> confirmReservations(event);
            case "SaldoConfirmado", "AtivoConfirmado" -> recordEffect(event);
            case "OrdemLiquidada", "OrdemRejeitada" -> {
                steps.complete(event, FINALIZE_ORDER);
                sagas.advance(event.sagaId(), event.type().equals("OrdemLiquidada") ? CONCLUIDA : REJEITADA);
            }
            case "SaldoRecusado" -> {
                steps.complete(event, RESERVE_BALANCE);
                // No reservation exists to undo. Wait for the order service to persist its decision.
                steps.issue(event, FINALIZE_ORDER, event.payload().deepCopy().put("estado", "REJEITADA"));
                sagas.advance(event.sagaId(), COMPENSANDO);
            }
            case "AtivoRecusado" -> {
                steps.complete(event, RESERVE_ASSET);
                var release = event.payload().deepCopy();
                release.remove("motivo");
                steps.issue(event, RELEASE_BALANCE, release);
                sagas.advance(event.sagaId(), COMPENSANDO);
            }
            case "ReservaSaldoDesfeita" -> {
                steps.complete(event, RELEASE_BALANCE);
                var outcome = event.snapshot().put("estado", "REJEITADA");
                outcome.put("motivo", steps.assetRefusalReason(event.sagaId()));
                steps.issue(event, FINALIZE_ORDER, outcome);
            }
            case "LiquidacaoFalhou" -> {
                steps.complete(event, SETTLE);
                // Both reservations still need compensation before the order can be rejected.
                sagas.advance(event.sagaId(), RECUPERACAO_PENDENTE);
            }
            default -> throw new AmqpRejectAndDontRequeueException("Unexpected coordinator message: " + event.type());
        }
    }

    private void confirmReservations(SagaEnvelope decision) {
        steps.complete(decision, SETTLE);
        var balance = decision.payload().deepCopy();
        balance.remove("reservaAtivoId");
        var asset = decision.payload().deepCopy();
        asset.remove("reservaSaldoId");
        steps.issue(decision, CONFIRM_BALANCE, balance);
        steps.issue(decision, CONFIRM_ASSET, asset);
        sagas.advance(decision.sagaId(), EFETIVANDO);
    }

    private void recordEffect(SagaEnvelope confirmation) {
        steps.complete(confirmation, confirmation.type().equals("SaldoConfirmado") ? CONFIRM_BALANCE : CONFIRM_ASSET);
        if (steps.bothEffectsConfirmed(confirmation.sagaId())) {
            var outcome = confirmation.snapshot();
            outcome.put("estado", "LIQUIDADA");
            outcome.set("liquidacaoId", confirmation.payload().get("liquidacaoId"));
            steps.issue(confirmation, FINALIZE_ORDER, outcome);
        }
    }
}
