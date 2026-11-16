package io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ReservedBalanceTest {
    @Test
    void normalizesMoneyWithoutRounding() {
        assertThat(balance("12.5").amount()).isEqualTo(new BigDecimal("12.50"));
        assertThat(balance("99999999999999999.99").amount()).hasScaleOf(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.001", "100000000000000000.00"})
    void rejectsInvalidAmounts(String amount) {
        assertThatIllegalArgumentException().isThrownBy(() -> balance(amount));
    }

    @Test
    void requiresReservationCorrelationAndBuyer() {
        UUID id = UUID.randomUUID();
        assertThatNullPointerException().isThrownBy(() -> new ReservedBalance(null, id, id, "buyer", BigDecimal.ONE));
        assertThatNullPointerException().isThrownBy(() -> new ReservedBalance(id, null, id, "buyer", BigDecimal.ONE));
        assertThatNullPointerException().isThrownBy(() -> new ReservedBalance(id, id, null, "buyer", BigDecimal.ONE));
        assertThatNullPointerException().isThrownBy(() -> new ReservedBalance(id, id, id, "buyer", null));
        for (String buyer : new String[]{null, "", "  ", "x".repeat(65)}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new ReservedBalance(id, id, id, buyer, BigDecimal.ONE));
        }
    }

    private ReservedBalance balance(String amount) {
        return new ReservedBalance(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "buyer", new BigDecimal(amount));
    }
}
