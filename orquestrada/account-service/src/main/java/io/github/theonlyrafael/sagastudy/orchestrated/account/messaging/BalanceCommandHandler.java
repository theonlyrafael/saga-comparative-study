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
        try {
            return switch (command.type()) {
                case "ReservarSaldo" -> reserve(command);
                case "ConfirmarReservaSaldo" -> transition(command, true);
                case "DesfazerReservaSaldo" -> transition(command, false);
                default -> throw new AmqpRejectAndDontRequeueException("Unexpected reservation command");
            };
        } catch (BalanceReservationException conflict) {
            throw new AmqpRejectAndDontRequeueException("Incompatible reservation command", conflict);
        }
    }

    private CommandReply transition(CommandEnvelope command, boolean confirm) {
        var id = command.resource("reservaSaldoId");
        var snapshot = command.payload().deepCopy();
        snapshot.remove("reservaSaldoId");
        snapshot.remove("liquidacaoId");
        boolean matches = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM balance_reservations r
                    LEFT JOIN outbox_messages o ON o.operation_id = r.operation_id AND o.consumer_name = ?
                    WHERE r.reservation_id = ? AND r.order_id = ? AND r.saga_id = ?
                    AND r.account_id = ? AND r.amount = ?
                    AND (o.request IS NULL OR ((o.request->'payload') - 'reservaSaldoId') = ?::jsonb))
                """, Boolean.class, "saga.v1.account", id, command.orderId(), command.sagaId(),
                command.payload().get("clienteId").asString(), new BigDecimal(command.payload().get("valorTotal").asString()), snapshot.toString()));
        if (!matches) throw new AmqpRejectAndDontRequeueException("Reservation and command do not match");
        if (confirm) {
            reservations.confirm(id);
            return CommandReply.of(command, "SaldoConfirmado");
        }
        reservations.release(id);
        return CommandReply.of(command, "ReservaSaldoDesfeita");
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
