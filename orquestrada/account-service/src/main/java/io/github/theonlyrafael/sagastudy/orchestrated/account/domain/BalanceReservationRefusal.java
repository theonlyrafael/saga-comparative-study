package io.github.theonlyrafael.sagastudy.orchestrated.account.domain;

/** Expected reservation refusal raised before any domain mutation. */
public class BalanceReservationRefusal extends BalanceReservationException {
    public BalanceReservationRefusal(Reason reason, String message) {
        super(reason, message);
    }
}
