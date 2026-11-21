package io.github.theonlyrafael.sagastudy.orchestrated.account.messaging;

import com.rabbitmq.client.ConnectionFactory;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.ParticipantConsumer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false", "outbox.poll-interval=100ms"
})
class AccountBrokerIntegrationTest {
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5")
            .asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> BROKER = new GenericContainer<>(DockerImageName.parse(
            "rabbitmq:4.4.0-management@sha256:af6f50d38098d8bd52fef7f3fa85015dd8b2bf6def58e995bf584ae9ba0f6407"))
            .withEnv("RABBITMQ_DEFAULT_USER", "tester").withEnv("RABBITMQ_DEFAULT_PASS", "test-only")
            .withExposedPorts(5672).waitingFor(Wait.forListeningPort());

    @DynamicPropertySource static void connections(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("spring.rabbitmq.host", BROKER::getHost);
        r.add("spring.rabbitmq.port", () -> BROKER.getMappedPort(5672));
        r.add("spring.rabbitmq.username", () -> "tester");
        r.add("spring.rabbitmq.password", () -> "test-only");
        r.add("spring.rabbitmq.virtual-host", () -> "/");
    }

    private static final String QUEUE = "saga.v1.account";
    private static final String COMMAND = "ReservarSaldo";
    @Autowired ParticipantConsumer consumer;
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitListenerEndpointRegistry listeners;

    @BeforeEach
    void prepareRoutes() {
        var admin = new RabbitAdmin(rabbit);
        var commands = new DirectExchange("saga.v1.commands");
        var responses = new DirectExchange("saga.v1.responses");
        var input = QueueBuilder.durable(QUEUE).deadLetterExchange("").deadLetterRoutingKey("bad-input").build();
        var output = new Queue("replies");
        admin.declareExchange(commands);
        admin.declareExchange(responses);
        admin.declareQueue(input);
        admin.declareQueue(output);
        admin.declareQueue(new Queue("bad-input"));
        admin.declareBinding(BindingBuilder.bind(input).to(commands).with(COMMAND));
        admin.declareBinding(BindingBuilder.bind(output).to(responses).with("SaldoReservado"));
    }

    @AfterEach
    void stopListeners() {
        listeners.stop();
    }

    private Message fundedCommand() {
        String buyer = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO accounts (account_id, available_balance) VALUES (?, 100)", buyer);
        String body = """
                {"schemaVersion":1,"type":"ReservarSaldo","messageId":"%s","operationId":"%s",
                 "orderId":"%s","sagaId":"%s","occurredAt":"2026-11-20T00:00:00Z",
                 "payload":{"clienteId":"%s","ativoId":"ASSET","precoUnitario":"10.00","quantidade":2,
                            "valorTotal":"20.00","versaoCatalogo":"v1"}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), buyer);
        return new Message(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void closingChannelAfterDatabaseCommitRedeliversTheStoredReply() throws Exception {
        rabbit.send("saga.v1.commands", COMMAND, fundedCommand());
        var factory = new ConnectionFactory();
        factory.setHost(BROKER.getHost());
        factory.setPort(BROKER.getMappedPort(5672));
        factory.setUsername("tester");
        factory.setPassword("test-only");
        Message first;
        try (var connection = factory.newConnection(); var channel = connection.createChannel()) {
            var delivery = channel.basicGet(QUEUE, false);
            assertThat(delivery).isNotNull();
            var properties = new MessageProperties();
            properties.setReceivedRoutingKey(delivery.getEnvelope().getRoutingKey());
            consumer.receive(new Message(delivery.getBody(), properties));
            first = rabbit.receive("replies", 10000);
            assertThat(first).isNotNull();
            // Closing the channel with delivery still unacknowledged forces a broker redelivery.
        }
        listeners.start();
        var replay = rabbit.receive("replies", 10000);
        assertThat(replay).isNotNull();
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getMessageProperties().getMessageId()).isEqualTo(first.getMessageProperties().getMessageId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE reserved_balance=20",
                Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_messages WHERE message_id=?",
                Integer.class, UUID.fromString(first.getMessageProperties().getMessageId()))).isOne();
    }

    @Test
    void invalidInputIsDeadLettered() {
        listeners.start();
        var invalid = new Message("{}".getBytes(StandardCharsets.UTF_8));
        rabbit.send("saga.v1.commands", COMMAND, invalid);
        assertThat(rabbit.receive("bad-input", 10000)).isNotNull();
    }
}
