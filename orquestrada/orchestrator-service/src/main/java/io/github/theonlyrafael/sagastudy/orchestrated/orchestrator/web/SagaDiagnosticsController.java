package io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.web;

import io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.domain.SagaStatus;
import io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.repository.SagaRepository;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "saga.diagnostics.enabled", havingValue = "true")
public class SagaDiagnosticsController {
    private final SagaRepository sagas;

    public SagaDiagnosticsController(SagaRepository sagas) {
        this.sagas = sagas;
    }

    @GetMapping("/api/sagas/{sagaId}")
    public ResponseEntity<SagaStatus> find(@PathVariable UUID sagaId) {
        return ResponseEntity.of(sagas.find(sagaId));
    }
}
