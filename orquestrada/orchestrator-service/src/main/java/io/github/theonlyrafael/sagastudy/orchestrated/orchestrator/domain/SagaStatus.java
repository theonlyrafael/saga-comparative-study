package io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.domain;

import java.util.UUID;

public record SagaStatus(UUID sagaId, UUID orderId, SagaState estado) {}
