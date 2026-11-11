package io.github.theonlyrafael.sagastudy.orchestrated.custody.domain;

/** Internal states; external message names are defined separately by the Saga contract. */
public enum AssetReservationStatus {
    RESERVED,
    CONFIRMED,
    RELEASED
}
