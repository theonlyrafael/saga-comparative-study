package io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ReservedAssetTest {
    @Test
    void acceptsPositiveQuantityAndStableIdentifiers() {
        UUID id = UUID.randomUUID();
        var asset = new ReservedAsset(id, id, id, "buyer", "asset", Integer.MAX_VALUE);
        assertThat(asset.quantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(asset.reservationId()).isEqualTo(id);
    }

    @Test
    void rejectsMissingIdentifiersAndNonPositiveQuantity() {
        UUID id = UUID.randomUUID();
        assertThatNullPointerException().isThrownBy(() -> new ReservedAsset(null, id, id, "buyer", "asset", 1));
        assertThatNullPointerException().isThrownBy(() -> new ReservedAsset(id, null, id, "buyer", "asset", 1));
        assertThatNullPointerException().isThrownBy(() -> new ReservedAsset(id, id, null, "buyer", "asset", 1));
        for (String text : new String[]{null, "", " "}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new ReservedAsset(id, id, id, text, "asset", 1));
            assertThatIllegalArgumentException().isThrownBy(() -> new ReservedAsset(id, id, id, "buyer", text, 1));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new ReservedAsset(id, id, id, "x".repeat(65), "asset", 1));
        for (int quantity : new int[]{0, -1}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new ReservedAsset(id, id, id, "buyer", "asset", quantity));
        }
    }
}
