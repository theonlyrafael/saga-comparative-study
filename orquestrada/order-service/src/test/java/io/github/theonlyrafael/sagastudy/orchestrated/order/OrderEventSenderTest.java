package io.github.theonlyrafael.sagastudy.orchestrated.order;

import io.github.theonlyrafael.sagastudy.orchestrated.messaging.ResponseSender;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.PendingMessage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderEventSenderTest {
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final PendingMessage event = new PendingMessage(UUID.randomUUID(), "OrdemRecebida", "{}");

    @Test void nackDoesNotConfirmDelivery() throws Exception {
        doAnswer(invocation -> {
            CorrelationData attempt = invocation.getArgument(3);
            attempt.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(new ResponseSender(rabbit, 1000).send(event)).isFalse();
    }

    @Test void missingConfirmationTimesOut() {
        assertThatThrownBy(() -> new ResponseSender(rabbit, 20).send(event))
                .isInstanceOf(TimeoutException.class);
    }
}
