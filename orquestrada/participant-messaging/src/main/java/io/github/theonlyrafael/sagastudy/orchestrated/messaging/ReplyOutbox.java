package io.github.theonlyrafael.sagastudy.orchestrated.messaging;

import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

public interface ReplyOutbox {
    void append(String consumer, SagaEnvelope command, UUID messageId, ObjectNode reply);
}
