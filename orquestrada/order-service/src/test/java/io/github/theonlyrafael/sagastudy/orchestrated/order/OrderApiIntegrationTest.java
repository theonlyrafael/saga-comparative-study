package io.github.theonlyrafael.sagastudy.orchestrated.order;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.ObjectMapper;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;

@ActiveProfiles("demo")
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.rabbitmq.username=unused",
            "spring.rabbitmq.password=unused",
            "spring.rabbitmq.dynamic=false",
            "management.health.rabbit.enabled=false",
            "management.endpoint.health.group.readiness.include=db"
        })
class OrderApiIntegrationTest {
    @Container
    static final PostgreSQLContainer DB =
            new PostgreSQLContainer(
                    DockerImageName.parse(
                                    "postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5")
                            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();
    final String body = "{\"clienteId\":\"C\",\"ativoId\":\"A\",\"quantidade\":3}";

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM http_idempotency");
        jdbc.update("DELETE FROM outbox_messages");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM asset_catalog");
        jdbc.update("INSERT INTO asset_catalog VALUES ('A','v1',10.00), ('B','v1',25.50)");
    }

    HttpResponse<String> post(String key, String body) throws Exception {
        var b =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ordens"))
                        .timeout(java.time.Duration.ofSeconds(20))
                        .header("Content-Type", "application/json");
        if (key != null) b.header("Idempotency-Key", key);
        return http.send(
                b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void createsAndQueriesCapturedOrder() throws Exception {
        var response = post("k", body);
        assertThat(response.statusCode()).isEqualTo(202);
        var tree = json.readTree(response.body());
        assertThat(tree.get("estado").asText()).isEqualTo("RECEBIDA");
        assertThat(tree.get("valorTotal").decimalValue()).isEqualByComparingTo("30");
        assertThat(tree.get("sagaId").asText()).isNotBlank();
        var location = response.headers().firstValue("Location").orElseThrow();
        assertThat(get(location).body()).isEqualTo(response.body());
    }

    @Test
    void replayPreservesPriceEvenAfterCatalogRemoval() throws Exception {
        var first = post("k", body);
        jdbc.update("DELETE FROM asset_catalog");
        var second = post("k", body);
        assertThat(second.statusCode()).isEqualTo(202);
        assertThat(second.body()).isEqualTo(first.body());
    }

    @Test
    void newOrdersCaptureNewCatalogVersion() throws Exception {
        var first = post("k", body);
        jdbc.update(
                "UPDATE asset_catalog SET unit_price=20,catalog_version='v2' WHERE asset_id='A'");
        var second = post("other", body);
        assertThat(json.readTree(second.body()).get("valorTotal").decimalValue())
                .isEqualByComparingTo("60");
        assertThat(json.readTree(second.body()).get("versaoCatalogo").asText()).isEqualTo("v2");
        assertThat(post("k", body).body()).isEqualTo(first.body());
    }

    @Test
    void conflictsDoNotChangeOriginalOrder() throws Exception {
        var first = post("k", body);
        assertThat(post("k", body.replace(":3", ":4")).statusCode()).isEqualTo(409);
        assertThat(post("k", body.replace("\"A\"", "\"B\"")).statusCode()).isEqualTo(409);
        assertThat(post("k", body).body()).isEqualTo(first.body());
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void keysAreScopedToClient() throws Exception {
        assertThat(post("k", body).statusCode()).isEqualTo(202);
        assertThat(post("k", body.replace("\"C\"", "\"D\"")).statusCode()).isEqualTo(202);
        assertThat(count("orders")).isEqualTo(2);
    }

    @Test
    void missingAssetRollsBackKeyAndAllowsRetry() throws Exception {
        jdbc.update("DELETE FROM asset_catalog");
        assertThat(post("k", body).statusCode()).isEqualTo(422);
        assertThat(count("http_idempotency")).isZero();
        assertThat(count("orders")).isZero();
        jdbc.update("INSERT INTO asset_catalog VALUES ('A','v1',10)");
        assertThat(post("k", body).statusCode()).isEqualTo(202);
    }

    @Test
    void overflowRollsBackKey() throws Exception {
        jdbc.update("UPDATE asset_catalog SET unit_price=99999999999999999.99");
        assertThat(post("k", body).statusCode()).isEqualTo(400);
        assertThat(count("http_idempotency")).isZero();
        assertThat(count("orders")).isZero();
    }

    @Test
    void unknownOrderIs404AndMalformedIdIs400() throws Exception {
        assertThat(get("/api/ordens/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(get("/api/ordens/invalid").statusCode()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "null",
                "{",
                "{\"clienteId\":\"C\",\"ativoId\":\"A\",\"quantidade\":0}",
                "{\"clienteId\":\"C\",\"ativoId\":\"A\",\"quantidade\":1.5}",
                "{\"clienteId\":\"C\",\"ativoId\":\"A\",\"quantidade\":1,\"extra\":true}"
            })
    void rejectsInvalidBody(String invalid) throws Exception {
        assertThat(post("k", invalid).statusCode()).isEqualTo(400);
        assertThat(count("orders")).isZero();
    }

    @Test
    void requiresKey() throws Exception {
        assertThat(post(null, body).statusCode()).isEqualTo(400);
    }

    @Test
    void concurrentDuplicatesCreateOneOrder() throws Exception {
        var results = concurrent(false);
        assertThat(results).allMatch(r -> r.statusCode() == 202);
        assertThat(results.stream().map(HttpResponse::body).distinct().count()).isEqualTo(1);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("http_idempotency")).isEqualTo(1);
    }

    @Test
    void concurrentDivergentPayloadsHaveOneWinner() throws Exception {
        var results = concurrent(true);
        assertThat(results.stream().filter(r -> r.statusCode() == 202).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.statusCode() == 409).count()).isEqualTo(5);
        assertThat(count("orders")).isEqualTo(1);
    }

    List<HttpResponse<String>> concurrent(boolean divergent) throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        var gate = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < 6; i++) {
                String payload = divergent ? body.replace(":3", ":" + (i + 1)) : body;
                futures.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return post("k", payload);
                                }));
            }
            gate.countDown();
            var results = new ArrayList<HttpResponse<String>>();
            for (var f : futures) results.add(f.get(30, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void persistenceFailureRollsBackOrderAndKey() throws Exception {
        jdbc.execute(
                "ALTER TABLE orders ADD CONSTRAINT test_storage_failure CHECK (quantidade <> 3)");
        try {
            var response = post("k", body);
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).doesNotContain("test_storage_failure");
            assertThat(count("http_idempotency")).isZero();
            assertThat(count("orders")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE orders DROP CONSTRAINT test_storage_failure");
        }
        assertThat(post("k", body).statusCode()).isEqualTo(202);
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
