package io.github.theonlyrafael.sagastudy.orchestrated.custody.domain;

public class AssetReservationException extends RuntimeException {

    public enum Reason {
        POSITION_NOT_FOUND,
        RESERVATION_NOT_FOUND,
        INSUFFICIENT_ASSETS,
        OPERATION_CONFLICT,
        INVALID_TRANSITION
    }

    private final Reason reason;

    public AssetReservationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
