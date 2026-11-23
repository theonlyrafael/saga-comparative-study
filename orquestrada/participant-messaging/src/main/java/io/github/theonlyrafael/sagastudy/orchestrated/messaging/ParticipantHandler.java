package io.github.theonlyrafael.sagastudy.orchestrated.messaging;

public interface ParticipantHandler {
    CommandReply handle(SagaEnvelope command);
}
