package io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.application;

import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SagaReconciler {
    private record PendingStep(UUID operationId, UUID sagaId, int retries) {}

    private final JdbcTemplate jdbc;
    private final long timeoutMs;
    private final int maxAttempts;
    private final long initialDelayMs;
    private final long maxDelayMs;
    private final int batchSize;

    public SagaReconciler(JdbcTemplate jdbc, @Value("${saga.timeout.step:30s}") Duration timeout,
            @Value("${saga.retry.max-attempts:5}") int maxAttempts,
            @Value("${saga.retry.initial-delay:1s}") Duration initialDelay,
            @Value("${saga.retry.max-delay:1m}") Duration maxDelay,
            @Value("${reconciliation.batch-size:32}") int batchSize) {
        this.jdbc = jdbc;
        this.timeoutMs = timeout.toMillis();
        this.initialDelayMs = initialDelay.toMillis();
        this.maxDelayMs = maxDelay.toMillis();
        this.maxAttempts = maxAttempts;
        this.batchSize = batchSize;
        if (timeoutMs < 1 || initialDelayMs < 1 || maxDelayMs < initialDelayMs || maxAttempts < 1 || batchSize < 1) {
            throw new IllegalArgumentException("Invalid Saga recovery settings");
        }
    }

    @Scheduled(fixedDelayString = "${reconciliation.poll-interval:5s}",
            initialDelayString = "${reconciliation.poll-interval:5s}")
    @Transactional
    public void reconcile() {
        var expired = jdbc.query("""
                SELECT s.operation_id, s.saga_id, s.retry_count FROM saga_steps s
                JOIN saga_instances saga ON saga.saga_id=s.saga_id
                WHERE s.completed_at IS NULL AND s.wait_started_at <= CURRENT_TIMESTAMP - ? * interval '1 ms'
                    AND saga.state NOT IN ('CONCLUIDA', 'REJEITADA')
                ORDER BY s.wait_started_at, s.operation_id LIMIT ? FOR UPDATE OF saga, s SKIP LOCKED
                """, (row, index) -> new PendingStep(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getInt(3)), timeoutMs, batchSize);
        for (var step : expired) recover(step);
    }

    private void recover(PendingStep step) {
        // A replay asks the participant Inbox for its durable result with all original identifiers.
        // An unpublished message, including one deferred by the publisher, keeps its delivery schedule.
        int rearmed = jdbc.update("""
                UPDATE outbox_messages SET published_at=NULL, next_attempt_at=CURRENT_TIMESTAMP
                WHERE operation_id=? AND published_at <= CURRENT_TIMESTAMP - ? * interval '1 ms'
                """, step.operationId(), timeoutMs);
        int retries = step.retries();
        long backoff = 0;
        if (rearmed == 1) {
            if (retries < maxAttempts) retries++;
            backoff = retryDelay(retries);
            if (retries >= maxAttempts) {
                jdbc.update("""
                        UPDATE saga_instances SET state='RECUPERACAO_PENDENTE', updated_at=CURRENT_TIMESTAMP
                        WHERE saga_id=? AND state <> 'RECUPERACAO_PENDENTE'
                        """, step.sagaId());
            }
        }
        jdbc.update("""
                UPDATE saga_steps SET retry_count=?, wait_started_at=CURRENT_TIMESTAMP + ? * interval '1 ms'
                WHERE operation_id=?
                """, retries, backoff, step.operationId());
    }

    private long retryDelay(int retries) {
        if (retries >= maxAttempts) return maxDelayMs;
        long delay = initialDelayMs;
        for (int i = 1; i < retries && delay < maxDelayMs; i++) {
            delay += Math.min(delay, maxDelayMs - delay);
        }
        return delay;
    }
}
