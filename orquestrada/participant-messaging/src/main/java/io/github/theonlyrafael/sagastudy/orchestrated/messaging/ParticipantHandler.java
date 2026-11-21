package io.github.theonlyrafael.sagastudy.orchestrated.messaging;

public interface ParticipantHandler {
    CommandReply handle(CommandEnvelope command);
}
