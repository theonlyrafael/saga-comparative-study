package io.github.theonlyrafael.sagastudy.orchestrated.settlement.application;

import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ConfirmSettlementCommandTest {
    private final UUID order = UUID.randomUUID();
    private final UUID saga = UUID.randomUUID();
    private final ReservedBalance balance = new ReservedBalance(UUID.randomUUID(), order, saga, "buyer", new BigDecimal("30"));
    private final ReservedAsset asset = new ReservedAsset(UUID.randomUUID(), order, saga, "buyer", "asset", 3);

    @Test
    void preservesCapturedPriceAndCatalogVersion() {
        var command = command(balance, asset, "10.0");
        assertThat(command.unitPrice()).isEqualTo(new BigDecimal("10.00"));
        assertThat(command.catalogVersion()).isEqualTo("catalog-v1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.001", "100000000000000000.00"})
    void rejectsInvalidPrice(String price) {
        assertThatIllegalArgumentException().isThrownBy(() -> command(balance, asset, price));
    }

    @Test
    void requiresBothReservationsAndOrderPriceMetadata() {
        assertThatNullPointerException().isThrownBy(() -> command(null, asset, "10"));
        assertThatNullPointerException().isThrownBy(() -> command(balance, null, "10"));
        assertThatNullPointerException().isThrownBy(() -> new ConfirmSettlementCommand(null, balance, asset, BigDecimal.TEN, "v1"));
        assertThatNullPointerException().isThrownBy(() -> new ConfirmSettlementCommand(UUID.randomUUID(), balance, asset, null, "v1"));
        for (String version : new String[]{null, "", " "}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new ConfirmSettlementCommand(UUID.randomUUID(), balance, asset, BigDecimal.TEN, version));
        }
    }

    private ConfirmSettlementCommand command(ReservedBalance money, ReservedAsset stock, String price) {
        return new ConfirmSettlementCommand(UUID.randomUUID(), money, stock, new BigDecimal(price), "catalog-v1");
    }
}
