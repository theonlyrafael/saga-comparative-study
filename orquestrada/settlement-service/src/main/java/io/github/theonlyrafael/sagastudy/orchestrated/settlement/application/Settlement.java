package io.github.theonlyrafael.sagastudy.orchestrated.settlement.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable local decision. Its presence means CONFIRMED, never that downstream effects are complete. */
public record Settlement(UUID id, ConfirmSettlementCommand command, Instant confirmedAt) {
    public Settlement {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(command, "command is required");
        Objects.requireNonNull(confirmedAt, "confirmedAt is required");
    }
}
