package io.github.theonlyrafael.sagastudy.orchestrated.account.messaging;

import io.github.theonlyrafael.sagastudy.orchestrated.account.application.BalanceReservationService;
import io.github.theonlyrafael.sagastudy.orchestrated.account.application.ReserveBalanceCommand;
import io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservationRefusal;
import io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservationException;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.CommandEnvelope;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.CommandReply;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.ParticipantHandler;
import java.math.BigDecimal;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class BalanceCommandHandler implements ParticipantHandler {
    private final BalanceReservationService reservations;
    private final JdbcTemplate jdbc;

    public BalanceCommandHandler(BalanceReservationService reservations, JdbcTemplate jdbc) {
        this.reservations = reservations;
        this.jdbc = jdbc;
    }

    @Override
    public CommandReply handle(CommandEnvelope command) {
        if (!command.type().equals("ReservarSaldo")) {
            throw new AmqpRejectAndDontRequeueException("Unexpected reservation command");
        }
        return reserve(command);
    }

    private CommandReply reserve(CommandEnvelope command) {
        var payload = command.payload();
        try {
            var id = reservations.reserve(new ReserveBalanceCommand(command.orderId(), command.sagaId(),
                    command.operationId(), payload.get("clienteId").asString(),
                    new BigDecimal(payload.get("valorTotal").asString())));
            return CommandReply.of(command, "SaldoReservado").with("reservaSaldoId", id.toString());
        } catch (BalanceReservationRefusal refusal) {
            String reason = switch (refusal.getReason()) {
                case ACCOUNT_NOT_FOUND -> "CONTA_INEXISTENTE";
                case INSUFFICIENT_BALANCE -> "SALDO_INSUFICIENTE";
                default -> throw refusal;
            };
            return CommandReply.of(command, "SaldoRecusado").with("motivo", reason);
        }
    }
}
