package io.github.theonlyrafael.sagastudy.orchestrated.custody.domain;

/** Expected reservation refusal raised before any domain mutation. */
public class AssetReservationRefusal extends AssetReservationException {
    public AssetReservationRefusal(Reason reason, String message) {
        super(reason, message);
    }
}
