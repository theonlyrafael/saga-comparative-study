package io.github.theonlyrafael.sagastudy.orchestrated.custody.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetReservationTest {
    private final UUID order = UUID.randomUUID();
    private final UUID saga = UUID.randomUUID();
    private final UUID operation = UUID.randomUUID();
    private final UUID source = UUID.randomUUID();

    @Test
    void createsReservationWithOriginalSourceAndQuantity() {
        var reservation = reservation();
        assertNotNull(reservation.getId());
        assertEquals(source, reservation.getSourcePositionId());
        assertEquals("BUYER", reservation.getBuyerId());
        assertEquals("ASSET", reservation.getAssetId());
        assertEquals(3, reservation.getQuantity());
        assertEquals(AssetReservationStatus.RESERVED, reservation.getStatus());
    }

    @Test
    void comparesEveryBusinessFieldForReplay() {
        var reservation = reservation();
        assertTrue(reservation.matches(order, saga, "BUYER", "ASSET", 3));
        assertFalse(reservation.matches(UUID.randomUUID(), saga, "BUYER", "ASSET", 3));
        assertFalse(reservation.matches(order, UUID.randomUUID(), "BUYER", "ASSET", 3));
        assertFalse(reservation.matches(order, saga, "OTHER", "ASSET", 3));
        assertFalse(reservation.matches(order, saga, "BUYER", "OTHER", 3));
        assertFalse(reservation.matches(order, saga, "BUYER", "ASSET", 4));
    }

    @Test
    void confirmationIsTerminalAndPreservesOriginalData() {
        var reservation = reservation();
        reservation.confirm();
        assertEquals(AssetReservationStatus.CONFIRMED, reservation.getStatus());
        assertEquals(AssetReservationException.Reason.INVALID_TRANSITION,
                assertThrows(AssetReservationException.class, reservation::release).getReason());
        assertThrows(AssetReservationException.class, reservation::confirm);
        assertTrue(reservation.matches(order, saga, "BUYER", "ASSET", 3));
        assertEquals(source, reservation.getSourcePositionId());
    }

    @Test
    void releaseIsTerminalAndPreservesOriginalData() {
        var reservation = reservation();
        reservation.release();
        assertEquals(AssetReservationStatus.RELEASED, reservation.getStatus());
        assertEquals(AssetReservationException.Reason.INVALID_TRANSITION,
                assertThrows(AssetReservationException.class, reservation::confirm).getReason());
        assertThrows(AssetReservationException.class, reservation::release);
        assertTrue(reservation.matches(order, saga, "BUYER", "ASSET", 3));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void requiresEveryIdentifier(int missing) {
        UUID[] ids = {order, saga, operation, source};
        ids[missing] = null;
        assertThrows(NullPointerException.class,
                () -> AssetReservation.reserve(ids[0], ids[1], ids[2], ids[3], "BUYER", "ASSET", 3));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\u2003"})
    void rejectsMissingBuyer(String buyer) {
        assertThrows(IllegalArgumentException.class,
                () -> AssetReservation.reserve(order, saga, operation, source, buyer, "ASSET", 3));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\u2003"})
    void rejectsMissingAsset(String asset) {
        assertThrows(IllegalArgumentException.class,
                () -> AssetReservation.reserve(order, saga, operation, source, "BUYER", asset, 3));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonpositiveQuantity(int quantity) {
        assertThrows(IllegalArgumentException.class,
                () -> AssetReservation.reserve(order, saga, operation, source, "BUYER", "ASSET", quantity));
    }

    @Test
    void supportsMaximumCommandQuantity() {
        assertEquals(Integer.MAX_VALUE, AssetReservation.reserve(order, saga, operation, source,
                "BUYER", "ASSET", Integer.MAX_VALUE).getQuantity());
    }

    private AssetReservation reservation() {
        return AssetReservation.reserve(order, saga, operation, source, "BUYER", "ASSET", 3);
    }
}
