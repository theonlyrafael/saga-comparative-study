package io.github.theonlyrafael.sagastudy.orchestrated.account.domain;

public class BalanceReservationException extends RuntimeException {

    public enum Reason {
        ACCOUNT_NOT_FOUND,
        RESERVATION_NOT_FOUND,
        INSUFFICIENT_BALANCE,
        OPERATION_CONFLICT,
        INVALID_TRANSITION
    }

    private final Reason reason;

    public BalanceReservationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
