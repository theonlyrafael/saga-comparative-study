package io.github.theonlyrafael.sagastudy.orchestrated.custody.messaging;

import io.github.theonlyrafael.sagastudy.orchestrated.custody.application.AssetReservationService;
import io.github.theonlyrafael.sagastudy.orchestrated.custody.application.ReserveAssetCommand;
import io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationRefusal;
import io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationException;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.SagaEnvelope;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.CommandReply;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.ParticipantHandler;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AssetCommandHandler implements ParticipantHandler {
    private final AssetReservationService reservations;
    private final JdbcTemplate jdbc;

    public AssetCommandHandler(AssetReservationService reservations, JdbcTemplate jdbc) {
        this.reservations = reservations;
        this.jdbc = jdbc;
    }

    @Override
    public CommandReply handle(SagaEnvelope command) {
        try {
            return switch (command.type()) {
                case "ReservarAtivo" -> reserve(command);
                case "ConfirmarReservaAtivo" -> transition(command, true);
                case "DesfazerReservaAtivo" -> transition(command, false);
                default -> throw new AmqpRejectAndDontRequeueException("Unexpected reservation command");
            };
        } catch (AssetReservationException | IllegalArgumentException conflict) {
            throw new AmqpRejectAndDontRequeueException("Incompatible reservation command", conflict);
        }
    }

    private CommandReply transition(SagaEnvelope command, boolean confirm) {
        var id = command.resource("reservaAtivoId");
        var snapshot = command.payload().deepCopy();
        snapshot.remove("reservaAtivoId");
        snapshot.remove("liquidacaoId");
        boolean matches = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM asset_reservations r
                    LEFT JOIN outbox_messages o ON o.operation_id = r.operation_id AND o.consumer_name = ?
                    WHERE r.reservation_id = ? AND r.order_id = ? AND r.saga_id = ?
                    AND r.buyer_id = ? AND r.asset_id = ? AND r.quantity = ?
                    AND (o.request IS NULL OR ((o.request->'payload') - 'reservaSaldoId') = ?::jsonb))
                """, Boolean.class, "saga.v1.custody", id, command.orderId(), command.sagaId(),
                command.payload().get("clienteId").asString(), command.payload().get("ativoId").asString(),
                command.payload().get("quantidade").asInt(), snapshot.toString()));
        if (!matches) throw new AmqpRejectAndDontRequeueException("Reservation and command do not match");
        if (confirm) {
            reservations.confirm(id);
            return CommandReply.of(command, "AtivoConfirmado");
        }
        reservations.release(id);
        return CommandReply.of(command, "ReservaAtivoDesfeita");
    }

    private CommandReply reserve(SagaEnvelope command) {
        var payload = command.payload();
        try {
            var id = reservations.reserve(new ReserveAssetCommand(command.orderId(), command.sagaId(),
                    command.operationId(), payload.get("clienteId").asString(), payload.get("ativoId").asString(),
                    payload.get("quantidade").asInt()));
            return CommandReply.of(command, "AtivoReservado").with("reservaAtivoId", id.toString());
        } catch (AssetReservationRefusal refusal) {
            String reason = switch (refusal.getReason()) {
                case POSITION_NOT_FOUND -> "POSICAO_INEXISTENTE";
                case INSUFFICIENT_ASSETS -> "ATIVO_INSUFICIENTE";
                default -> throw refusal;
            };
            return CommandReply.of(command, "AtivoRecusado").with("motivo", reason);
        }
    }
}
