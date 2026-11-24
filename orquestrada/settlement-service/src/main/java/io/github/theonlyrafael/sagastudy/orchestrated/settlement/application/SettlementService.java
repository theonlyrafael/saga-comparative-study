package io.github.theonlyrafael.sagastudy.orchestrated.settlement.application;

import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.SettlementConflictException;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.SettlementDeniedException;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.repository.SettlementRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = SettlementDeniedException.class)
public class SettlementService {
    private final SettlementRepository settlements;

    public SettlementService(SettlementRepository settlements) {
        this.settlements = settlements;
    }

    public Settlement confirm(ConfirmSettlementCommand command) {
        return decide(command, false);
    }

    /** Caller must supply successful reservation snapshots. The first committed decision prevails. */
    public Settlement decide(ConfirmSettlementCommand command, boolean requestDenial) {
        Objects.requireNonNull(command, "command is required");
        if (!settlements.admit(command, requestDenial)) throw new SettlementDeniedException();
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
