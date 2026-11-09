package io.github.theonlyrafael.sagastudy.orchestrated.custody.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReserveAssetCommandTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 3, Integer.MAX_VALUE})
    void acceptsPositiveQuantityAndPreservesReservationData(int quantity) {
        UUID orderId = UUID.randomUUID();
        UUID sagaId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();

        var command = new ReserveAssetCommand(
                orderId, sagaId, operationId, "CLI001", "ATV002", quantity
        );

        assertAll(
                () -> assertEquals(orderId, command.orderId()),
                () -> assertEquals(sagaId, command.sagaId()),
                () -> assertEquals(operationId, command.operationId()),
                () -> assertEquals("CLI001", command.buyerId()),
                () -> assertEquals("ATV002", command.assetId()),
                () -> assertEquals(quantity, command.quantity())
        );
    }

    @Test
    void requiresOrderId() {
        assertThrows(NullPointerException.class, () -> new ReserveAssetCommand(
                null, UUID.randomUUID(), UUID.randomUUID(), "CLI001", "ATV002", 1
        ));
    }

    @Test
    void requiresSagaId() {
        assertThrows(NullPointerException.class, () -> new ReserveAssetCommand(
                UUID.randomUUID(), null, UUID.randomUUID(), "CLI001", "ATV002", 1
        ));
    }

    @Test
    void requiresOperationId() {
        assertThrows(NullPointerException.class, () -> new ReserveAssetCommand(
                UUID.randomUUID(), UUID.randomUUID(), null, "CLI001", "ATV002", 1
        ));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n", "\u2003"})
    void rejectsMissingOrBlankBuyer(String buyerId) {
        assertThrows(IllegalArgumentException.class,
                () -> command(buyerId, "ATV002", 1));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n", "\u2003"})
    void rejectsMissingOrBlankAsset(String assetId) {
        assertThrows(IllegalArgumentException.class,
                () -> command("CLI001", assetId, 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonpositiveQuantity(int quantity) {
        assertThrows(IllegalArgumentException.class,
                () -> command("CLI001", "ATV002", quantity));
    }

    private ReserveAssetCommand command(String buyerId, String assetId, int quantity) {
        return new ReserveAssetCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                buyerId, assetId, quantity
        );
    }
}
