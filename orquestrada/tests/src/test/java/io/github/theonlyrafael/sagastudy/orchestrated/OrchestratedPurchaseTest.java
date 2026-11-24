package io.github.theonlyrafael.sagastudy.orchestrated;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Black-box acceptance test against the five running demo services in an exclusive demo-v1 environment. */
class OrchestratedPurchaseTest {
    private final URI ordersApi = URI.create(System.getenv().getOrDefault("ORDER_BASE_URL", "http://127.0.0.1:8081"))
            .resolve("/api/ordens");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void onePurchaseSettlesOnceAndConservesCashAndAssets() throws Exception {
        var receipt = purchase("compra-exemplo-1", "ATV002", 3);
        var order = UUID.fromString(receipt.get("orderId").asString());
        var saga = UUID.fromString(receipt.get("sagaId").asString());
        assertThat(receipt.get("estado").asString()).isEqualTo("RECEBIDA");
        assertThat(receipt.get("valorTotal").decimalValue()).isEqualByComparingTo("76.50");

        var result = awaitOrder(order, "LIQUIDADA");
        var settlement = UUID.fromString(result.get("liquidacaoId").asString());
        assertThat(result.get("sagaId")).isEqualTo(receipt.get("sagaId"));
        assertThat(purchase("compra-exemplo-1", "ATV002", 3)).isEqualTo(receipt);

        var coordinator = database("orchestrator");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(coordinator.queryForObject("SELECT state FROM saga_instances WHERE saga_id=? AND order_id=?",
                        String.class, saga, order)).isEqualTo("CONCLUIDA"));
        assertThat(coordinator.queryForObject("SELECT count(*) FROM saga_steps WHERE saga_id=? AND completed_at IS NOT NULL",
                Integer.class, saga)).isEqualTo(6);
        var orders = database("orders");
        assertThat(orders.queryForList("SELECT message_type FROM outbox_messages WHERE order_id=?", String.class, order))
                .containsExactlyInAnyOrder("OrdemRecebida", "OrdemLiquidada");

        var accounts = database("accounts");
        var balance = accounts.queryForObject("""
                SELECT reservation_id FROM balance_reservations WHERE order_id=? AND saga_id=?
                    AND account_id='CLI001' AND amount=76.50 AND status='CONFIRMED'
                """, UUID.class, order, saga);
        var cash = accounts.queryForMap("SELECT * FROM accounts WHERE account_id='CLI001'");
        assertThat((BigDecimal) cash.get("available_balance")).isEqualByComparingTo("923.50");
        assertThat((BigDecimal) cash.get("reserved_balance")).isEqualByComparingTo("0");
        assertThat((BigDecimal) cash.get("total_consumed")).isEqualByComparingTo("76.50");

        var custody = database("custody");
        var asset = custody.queryForObject("""
                SELECT reservation_id FROM asset_reservations WHERE order_id=? AND saga_id=?
                    AND buyer_id='CLI001' AND asset_id='ATV002' AND quantity=3 AND status='CONFIRMED'
                """, UUID.class, order, saga);
        assertPosition(custody, "COUNTERPARTY", "ATV002", 7);
        assertPosition(custody, "CLI001", "ATV002", 3);
        assertThat(database("settlements").queryForObject("""
                SELECT settlement_id FROM settlements WHERE order_id=? AND saga_id=?
                    AND balance_reservation_id=? AND asset_reservation_id=? AND status='CONFIRMED'
                    AND amount=76.50 AND catalog_version='demo-v1'
                """, UUID.class, order, saga, balance, asset)).isEqualTo(settlement);
    }

    @Test
    void insufficientStockReturnsTheReservedCashAndRejectsTheOrder() throws Exception {
        var accounts = database("accounts");
        String balances = "SELECT available_balance, reserved_balance, total_consumed FROM accounts WHERE account_id='CLI001'";
        var before = accounts.queryForMap(balances);
        String key = "compra-sem-estoque-1";
        var receipt = purchase(key, "ATV001", 2);
        var order = UUID.fromString(receipt.get("orderId").asString());
        var saga = UUID.fromString(receipt.get("sagaId").asString());

        var result = awaitOrder(order, "REJEITADA");
        assertThat(result.get("motivo").asString()).isEqualTo("ATIVO_INSUFICIENTE");
        assertThat(purchase(key, "ATV001", 2)).isEqualTo(receipt);
        var coordinator = database("orchestrator");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(coordinator.queryForObject("SELECT state FROM saga_instances WHERE saga_id=? AND order_id=?",
                        String.class, saga, order)).isEqualTo("REJEITADA"));
        assertThat(coordinator.queryForList("""
                SELECT response_type FROM saga_steps WHERE saga_id=?
                """, String.class, saga)).containsExactlyInAnyOrder(
                "SaldoReservado", "AtivoRecusado", "ReservaSaldoDesfeita", "OrdemRejeitada");

        assertThat(accounts.queryForObject("""
                SELECT status FROM balance_reservations WHERE order_id=? AND saga_id=? AND amount=20.00
                """, String.class, order, saga)).isEqualTo("RELEASED");
        assertThat(accounts.queryForMap(balances)).isEqualTo(before);
        var custody = database("custody");
        assertThat(custody.queryForObject("SELECT count(*) FROM asset_reservations WHERE order_id=?",
                Integer.class, order)).isZero();
        assertPosition(custody, "COUNTERPARTY", "ATV001", 1);
        assertThat(database("settlements").queryForObject("SELECT count(*) FROM settlements WHERE order_id=?",
                Integer.class, order)).isZero();
    }

    private JsonNode purchase(String key, String asset, int quantity) throws Exception {
        var body = json.createObjectNode().put("clienteId", "CLI001").put("ativoId", asset).put("quantidade", quantity);
        var request = HttpRequest.newBuilder(ordersApi).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        var receipt = json.readTree(response.body());
        assertThat(response.headers().firstValue("Location")).contains("/api/ordens/" + receipt.get("orderId").asString());
        return receipt;
    }

    private JsonNode awaitOrder(UUID order, String state) {
        var get = HttpRequest.newBuilder(ordersApi.resolve("/api/ordens/" + order))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return await().atMost(Duration.ofSeconds(60)).until(() -> status(get),
                result -> state.equals(result.get("estado").asString()));
    }

    private JsonNode status(HttpRequest get) throws Exception {
        var response = http.send(get, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private static void assertPosition(JdbcTemplate custody, String owner, String asset, long available) {
        var row = custody.queryForMap("SELECT * FROM positions WHERE owner_id=? AND asset_id=?", owner, asset);
        assertThat(row.get("available_quantity")).isEqualTo(available);
        assertThat(row.get("reserved_quantity")).isEqualTo(0L);
    }

    private static JdbcTemplate database(String owner) {
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("POSTGRES_PORT", "5433");
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + owner + "_db?connectTimeout=5&socketTimeout=5";
        return new JdbcTemplate(new DriverManagerDataSource(url, owner + "_user",
                System.getenv(owner.toUpperCase(Locale.ROOT) + "_DB_PASSWORD")));
    }
}
