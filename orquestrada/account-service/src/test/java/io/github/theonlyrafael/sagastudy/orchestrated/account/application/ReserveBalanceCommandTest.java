package io.github.theonlyrafael.sagastudy.orchestrated.account.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReserveBalanceCommandTest {

    @ParameterizedTest
    @ValueSource(strings = {"30", "30.0", "30.000"})
    void normalizesExactAmountsWithoutRounding(String amount) {
        assertEquals(new BigDecimal("30.00"), command("CLI001", amount).amount());
    }

    @Test
    void acceptsNumericBoundaryAndMaximumAccountLength() {
        var command = command("a".repeat(64), "99999999999999999.99");
        assertEquals(new BigDecimal("99999999999999999.99"), command.amount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.01", "0.001", "100000000000000000.00"})
    void rejectsAmountsOutsideMonetaryContract(String amount) {
        assertThrows(IllegalArgumentException.class, () -> command("CLI001", amount));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t"})
    void rejectsMissingAccount(String accountId) {
        assertThrows(IllegalArgumentException.class, () -> command(accountId, "1"));
    }

    @Test
    void rejectsAccountBeyondStorageLimit() {
        assertThrows(IllegalArgumentException.class, () -> command("a".repeat(65), "1"));
    }

    @Test
    void requiresEveryOperationIdentifierAndAmount() {
        UUID id = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("1.00");
        assertThrows(NullPointerException.class,
                () -> new ReserveBalanceCommand(null, id, id, "CLI001", amount));
        assertThrows(NullPointerException.class,
                () -> new ReserveBalanceCommand(id, null, id, "CLI001", amount));
        assertThrows(NullPointerException.class,
                () -> new ReserveBalanceCommand(id, id, null, "CLI001", amount));
        assertThrows(NullPointerException.class,
                () -> new ReserveBalanceCommand(id, id, id, "CLI001", null));
    }

    private ReserveBalanceCommand command(String accountId, String amount) {
        return new ReserveBalanceCommand(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), accountId, new BigDecimal(amount));
    }
}
