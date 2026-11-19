package io.github.theonlyrafael.sagastudy.orchestrated.order.messaging;

import java.util.UUID;

public record PendingMessage(UUID id, String type, String envelope) {}
