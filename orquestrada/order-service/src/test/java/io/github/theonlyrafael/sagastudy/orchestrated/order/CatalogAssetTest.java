package io.github.theonlyrafael.sagastudy.orchestrated.order;

import static org.assertj.core.api.Assertions.*;

import io.github.theonlyrafael.sagastudy.orchestrated.order.domain.CatalogAsset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

class CatalogAssetTest {
    @Test
    void computesExactTotal() {
        assertThat(new CatalogAsset("A", "v1", new BigDecimal("0.10")).total(3))
                .isEqualByComparingTo("0.30");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.001", "100000000000000000"})
    void rejectsInvalidPrice(String price) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CatalogAsset("A", "v1", new BigDecimal(price)));
    }

    @Test
    void rejectsTotalOverflow() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new CatalogAsset("A", "v1", new BigDecimal("99999999999999999.99"))
                                        .total(2));
    }
}
