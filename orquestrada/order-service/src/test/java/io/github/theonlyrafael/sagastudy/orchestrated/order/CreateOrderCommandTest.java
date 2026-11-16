package io.github.theonlyrafael.sagastudy.orchestrated.order;

import static org.assertj.core.api.Assertions.*;

import io.github.theonlyrafael.sagastudy.orchestrated.order.application.CreateOrderCommand;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CreateOrderCommandTest {
    @Test
    void acceptsStablePayload() {
        assertThat(new CreateOrderCommand("C", "A", 1, "k").quantidade()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " k", "k "})
    void rejectsInvalidKey(String key) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CreateOrderCommand("C", "A", 1, key));
    }

    @Test
    void rejectsMissingOversizedAndNonPositiveInput() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CreateOrderCommand(null, "A", 1, "k"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CreateOrderCommand("x".repeat(65), "A", 1, "k"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CreateOrderCommand("C", "x".repeat(129), 1, "k"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CreateOrderCommand("C", "A", 0, "k"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CreateOrderCommand("C", "A", -1, "k"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CreateOrderCommand("C", "A", 1, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CreateOrderCommand("C", "A", 1, "k".repeat(129)));
    }
}
