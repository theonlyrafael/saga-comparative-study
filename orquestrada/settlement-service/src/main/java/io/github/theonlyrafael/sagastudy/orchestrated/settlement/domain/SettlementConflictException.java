package io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain;

/** A correlation key or reservation already belongs to a different durable decision. */
public class SettlementConflictException extends RuntimeException {
    public SettlementConflictException() {
        super("The order, saga, operation or reservation already belongs to a different settlement");
    }
}
