package io.github.theonlyrafael.sagastudy.orchestrated.order;

import io.github.theonlyrafael.sagastudy.orchestrated.order.application.CreateOrderCommand;
import io.github.theonlyrafael.sagastudy.orchestrated.order.application.OrderService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "outbox.poll-interval=100ms", "outbox.retry-delay-ms=200", "spring.rabbitmq.connection-timeout=1s"
})
class OutboxPublisherIntegrationTest {
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

    @Autowired OrderService orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;

    @LocalServerPort int port;
    @Autowired ObjectMapper json;

    @Test void replayOverHttpPreservesPublishedEvent() throws Exception {
        var admin = new RabbitAdmin(rabbit);
        var exchange = new DirectExchange("saga.v1.responses");
        var queue = new Queue("http-test-" + UUID.randomUUID());
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("OrdemRecebida"));
        try {
            jdbc.update("INSERT INTO asset_catalog VALUES ('A','v1',10) ON CONFLICT DO NOTHING");
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ordens"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"clienteId":"C","ativoId":"A","quantidade":2}
                            """)).build();
            var client = HttpClient.newHttpClient();
            var created = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(202);
            var order = UUID.fromString(json.readTree(created.body()).get("orderId").asText());
            verifyDelivered(order, queue.getName());

            var replay = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(replay.statusCode()).isEqualTo(202);
            assertThat(replay.body()).isEqualTo(created.body());
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM outbox_messages WHERE order_id = ? AND published_at IS NOT NULL
                    """, Integer.class, order)).isEqualTo(1);
            assertThat(rabbit.receive(queue.getName(), 500)).isNull();
        } finally {
            admin.deleteQueue(queue.getName());
        }
    }

    @Test void returnedMessageRemainsPendingUntilRouteExists() {
        var admin = new RabbitAdmin(rabbit);
        var exchange = new DirectExchange("saga.v1.responses");
        admin.declareExchange(exchange);
        var queue = new Queue("orders-test-" + UUID.randomUUID());
        admin.declareQueue(queue);
        var order = createOrder();
        awaitDeferred(order);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("OrdemRecebida"));
        verifyDelivered(order, queue.getName());
        admin.deleteQueue(queue.getName());
    }

    @Test void resumesAfterBrokerOutage() throws Exception {
        var admin = new RabbitAdmin(rabbit);
        var exchange = new DirectExchange("saga.v1.responses");
        admin.declareExchange(exchange);
        var queue = new Queue("outage-test-" + UUID.randomUUID());
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("OrdemRecebida"));
        assertThat(BROKER.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        UUID order;
        try {
            order = createOrder();
            awaitDeferred(order);
        } finally {
            assertThat(BROKER.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
        }
        verifyDelivered(order, queue.getName());
        admin.deleteQueue(queue.getName());
    }

    private UUID createOrder() {
        jdbc.update("INSERT INTO asset_catalog VALUES ('A','v1',10) ON CONFLICT DO NOTHING");
        return orders.create(new CreateOrderCommand("C", "A", 2, UUID.randomUUID().toString())).orderId();
    }

    private void awaitDeferred(UUID order) {
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject("""
                SELECT published_at IS NULL AND next_attempt_at > created_at
                FROM outbox_messages WHERE order_id = ?
                """, Boolean.class, order));
    }

    private void verifyDelivered(UUID order, String queue) {
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox_messages WHERE order_id = ?", Boolean.class, order));
        var stored = jdbc.queryForMap("SELECT message_id, envelope::text AS body FROM outbox_messages WHERE order_id = ?", order);
        var received = rabbit.receive(queue, 5000);
        assertThat(received).isNotNull();
        assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).isEqualTo(stored.get("body"));
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo(stored.get("message_id").toString());
        assertThat(received.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }
}
