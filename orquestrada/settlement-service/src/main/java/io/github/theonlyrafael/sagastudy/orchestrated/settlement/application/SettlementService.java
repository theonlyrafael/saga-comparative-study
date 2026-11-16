package io.github.theonlyrafael.sagastudy.orchestrated.settlement.application;

import io.github.theonlyrafael.sagastudy.orchestrated.settlement.repository.SettlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.Objects;
import org.springframework.transaction.annotation.Isolation;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.SettlementConflictException;
import java.util.UUID;

@Service
public class SettlementService {
    private final SettlementRepository settlements;

    public SettlementService(SettlementRepository settlements) {
        this.settlements = settlements;
    }

    /** Caller must supply successful reservation snapshots, never unverified client input. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Settlement confirm(ConfirmSettlementCommand command) {
        Objects.requireNonNull(command, "command is required");
        settlements.insertIfAbsent(command);
        // A second statement observes the committed winner after ON CONFLICT waited for it.
        var decision = settlements.findByOperationId(command.operationId())
                .orElseThrow(SettlementConflictException::new);
        if (!decision.command().equals(command)) {
            throw new SettlementConflictException();
        }
        return decision;
    }

    /** Absence at one instant is not authorization to compensate an in-flight request. */
    @Transactional(readOnly = true)
    public Optional<Settlement> findByOperationId(UUID operationId) {
        return settlements.findByOperationId(operationId);
    }

    @Transactional(readOnly = true)
    public Optional<Settlement> findByOrderId(UUID orderId) {
        return settlements.findByOrderId(orderId);
    }

    @Transactional(readOnly = true)
    public Optional<Settlement> findBySagaId(UUID sagaId) {
        return settlements.findBySagaId(sagaId);
    }
}
