package io.github.theonlyrafael.sagastudy.orchestrated.account.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BalanceReservationTest {

    @Test
    void comparesBusinessPayloadByValue() {
        UUID order = UUID.randomUUID();
        UUID saga = UUID.randomUUID();
        var reservation = BalanceReservation.reserve(order, saga, UUID.randomUUID(),
                "CLI001", new BigDecimal("30.00"));

        assertEquals(BalanceReservationStatus.RESERVED, reservation.getStatus());
        assertTrue(reservation.matches(order, saga, "CLI001", new BigDecimal("30.0")));
        assertFalse(reservation.matches(UUID.randomUUID(), saga, "CLI001", new BigDecimal("30")));
        assertFalse(reservation.matches(order, UUID.randomUUID(), "CLI001", new BigDecimal("30")));
        assertFalse(reservation.matches(order, saga, "CLI002", new BigDecimal("30")));
        assertFalse(reservation.matches(order, saga, "CLI001", new BigDecimal("31")));
    }

    @Test
    void confirmationIsTerminal() {
        var reservation = reservation("30.00");
        reservation.confirm();
        assertEquals(BalanceReservationStatus.CONFIRMED, reservation.getStatus());
        assertEquals(BalanceReservationException.Reason.INVALID_TRANSITION,
                assertThrows(BalanceReservationException.class, reservation::release).getReason());
        assertThrows(BalanceReservationException.class, reservation::confirm);
    }

    @Test
    void releaseIsTerminal() {
        var reservation = reservation("30.00");
        reservation.release();
        assertEquals(BalanceReservationStatus.RELEASED, reservation.getStatus());
        assertEquals(BalanceReservationException.Reason.INVALID_TRANSITION,
                assertThrows(BalanceReservationException.class, reservation::confirm).getReason());
        assertThrows(BalanceReservationException.class, reservation::release);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-1.00", "0.001", "1", "100000000000000000.00"})
    void protectsMoneyInvariantWhenUsedWithoutCommand(String amount) {
        assertThrows(IllegalArgumentException.class, () -> reservation(amount));
    }

    @Test
    void requiresIdentifiersAccountAndAmount() {
        UUID id = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("1.00");
        assertThrows(IllegalArgumentException.class,
                () -> BalanceReservation.reserve(null, id, id, "CLI001", amount));
        assertThrows(IllegalArgumentException.class,
                () -> BalanceReservation.reserve(id, null, id, "CLI001", amount));
        assertThrows(IllegalArgumentException.class,
                () -> BalanceReservation.reserve(id, id, null, "CLI001", amount));
        assertThrows(IllegalArgumentException.class,
                () -> BalanceReservation.reserve(id, id, id, " ", amount));
        assertThrows(IllegalArgumentException.class,
                () -> BalanceReservation.reserve(id, id, id, "a".repeat(65), amount));
        assertThrows(IllegalArgumentException.class,
                () -> BalanceReservation.reserve(id, id, id, "CLI001", null));
    }

    private BalanceReservation reservation(String amount) {
        return BalanceReservation.reserve(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "CLI001", new BigDecimal(amount));
    }
}
