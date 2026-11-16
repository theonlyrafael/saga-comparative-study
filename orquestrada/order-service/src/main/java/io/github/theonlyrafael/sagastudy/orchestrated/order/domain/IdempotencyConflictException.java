package io.github.theonlyrafael.sagastudy.orchestrated.order.domain;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("Idempotency-Key already used with different content");
    }
}
