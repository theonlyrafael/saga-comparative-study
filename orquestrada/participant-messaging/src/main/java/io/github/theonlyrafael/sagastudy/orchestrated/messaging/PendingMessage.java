package io.github.theonlyrafael.sagastudy.orchestrated.messaging;

import java.util.UUID;

public record PendingMessage(UUID id, String type, String envelope, String traceparent) {}
