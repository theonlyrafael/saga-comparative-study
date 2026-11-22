package io.github.theonlyrafael.sagastudy.orchestrated.messaging;

import tools.jackson.databind.node.ObjectNode;

public record CommandReply(String type, ObjectNode payload) {
    public static CommandReply of(CommandEnvelope command, String type) {
        return new CommandReply(type, command.payload().deepCopy());
    }

    public CommandReply with(String field, String value) {
        payload.put(field, value);
        return this;
    }
}
