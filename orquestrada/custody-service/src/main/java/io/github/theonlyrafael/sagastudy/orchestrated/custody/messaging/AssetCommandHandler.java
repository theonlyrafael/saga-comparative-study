package io.github.theonlyrafael.sagastudy.orchestrated.custody.messaging;

import io.github.theonlyrafael.sagastudy.orchestrated.custody.application.AssetReservationService;
import io.github.theonlyrafael.sagastudy.orchestrated.custody.application.ReserveAssetCommand;
import io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationRefusal;
import io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationException;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.CommandEnvelope;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.CommandReply;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.ParticipantHandler;
import java.math.BigDecimal;
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
    public CommandReply handle(CommandEnvelope command) {
        if (!command.type().equals("ReservarAtivo")) {
            throw new AmqpRejectAndDontRequeueException("Unexpected reservation command");
        }
        return reserve(command);
    }

    private CommandReply reserve(CommandEnvelope command) {
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
