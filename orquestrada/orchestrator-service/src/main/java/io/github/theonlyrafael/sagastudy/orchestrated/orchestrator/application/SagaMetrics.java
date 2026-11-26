package io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SagaMetrics {
    private record Sample(String outcome, BigDecimal latency, BigDecimal compensation, BigDecimal convergence) {}

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final MeterRegistry registry;

    public SagaMetrics(JdbcTemplate jdbc, ApplicationEventPublisher events, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.events = events;
        this.registry = registry;
    }

    public void onCompletion(UUID sagaId) {
        var sample = jdbc.queryForObject("""
                SELECT state, latency_seconds, compensation_seconds, convergence_seconds
                FROM saga_measurements WHERE saga_id=?
                """, (row, index) -> new Sample(row.getString(1), row.getBigDecimal(2),
                row.getBigDecimal(3), row.getBigDecimal(4)), sagaId);
        events.publishEvent(sample);
    }

    @TransactionalEventListener
    public void record(Sample sample) {
        record("saga.order.latency", sample.outcome(), sample.latency());
        record("saga.compensation.duration", sample.outcome(), sample.compensation());
        record("saga.convergence.duration", sample.outcome(), sample.convergence());
    }

    private void record(String name, String outcome, BigDecimal seconds) {
        if (seconds == null || seconds.signum() < 0) return;
        Timer.builder(name).tag("outcome", outcome).publishPercentileHistogram().register(registry)
                .record(seconds.movePointRight(6).longValue(), TimeUnit.MICROSECONDS);
    }
}
