package io.github.theonlyrafael.sagastudy.orchestrated.settlement.application;

import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.*;
import io.github.theonlyrafael.sagastudy.orchestrated.settlement.repository.SettlementRepository;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.ParticipantConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.rabbitmq.username=unused", "spring.rabbitmq.password=unused",
        "spring.rabbitmq.dynamic=false",
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "outbox.publisher.enabled=false", "management.health.rabbit.enabled=false",
        "management.endpoint.health.group.readiness.include=db"
})
class SettlementServiceIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5")
            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired SettlementService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM settlements");
    }

    @Test
    void firstDecisionSurvivesAChangeInTheSimulatedOutcome() {
        var denied = command();
        assertThatThrownBy(() -> service.decide(denied, true)).isInstanceOf(SettlementDeniedException.class);
        assertThatThrownBy(() -> service.confirm(denied)).isInstanceOf(SettlementDeniedException.class);
        assertThat(service.findByOrderId(denied.balance().orderId())).isEmpty();

        var confirmed = command();
        var original = service.confirm(confirmed);
        assertThat(service.decide(confirmed, true)).isEqualTo(original);
        assertThat(service.findByOrderId(confirmed.balance().orderId())).contains(original);
    }

    @Test
    void competingDenialAndConfirmationObserveTheSameCommittedOutcome() throws Exception {
        var command = command();
        var executor = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var outcomes = new ArrayList<Future<Optional<Settlement>>>();
            for (boolean requestDenial : List.of(false, true)) {
                outcomes.add(executor.submit(() -> {
                    gate.await();
                    try { return Optional.of(service.decide(command, requestDenial)); }
                    catch (SettlementDeniedException denied) { return Optional.empty(); }
                }));
            }
            gate.countDown();
            var winner = outcomes.get(0).get(20, TimeUnit.SECONDS);
            assertThat(outcomes.get(1).get(20, TimeUnit.SECONDS)).isEqualTo(winner);
            assertThat(service.findByOrderId(command.balance().orderId())).isEqualTo(winner);
            assertThat(jdbc.queryForObject("SELECT outcome FROM settlement_decisions WHERE order_id=?",
                    String.class, command.balance().orderId())).isEqualTo(winner.isPresent() ? "CONFIRMED" : "DENIED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Autowired ParticipantConsumer consumer;
    @Autowired ObjectMapper json;

    private ObjectNode message() {
        var command = json.createObjectNode();
        command.put("type", "ConfirmarLiquidacao");
        command.put("schemaVersion", 1);
        for (String field : java.util.List.of("orderId", "sagaId", "operationId", "messageId")) {
            command.put(field, UUID.randomUUID().toString());
        }
        command.put("occurredAt", "2026-11-20T10:00:00Z");
        var payload = command.putObject("payload");
        payload.put("clienteId", "CLI001");
        payload.put("ativoId", "ASSET");
        payload.put("quantidade", 2);
        payload.put("precoUnitario", "10.00");
        payload.put("valorTotal", "20.00");
        payload.put("versaoCatalogo", "v1");
        payload.put("reservaSaldoId", UUID.randomUUID().toString());
        payload.put("reservaAtivoId", UUID.randomUUID().toString());
        return command;
    }

    private void deliver(ObjectNode command) {
        var properties = new MessageProperties();
        properties.setReceivedRoutingKey(command.get("type").asString());
        consumer.receive(new Message(json.writeValueAsBytes(command), properties));
    }

    private ObjectNode reply(ObjectNode command) {
        String envelope = jdbc.queryForObject("SELECT envelope::text FROM outbox_messages WHERE operation_id = ?",
                String.class, UUID.fromString(command.get("operationId").asString()));
        return (ObjectNode) json.readTree(envelope);
    }

    @Test
    void conflictingCommandDoesNotAuthorizeCompensationAfterSettlement() {
        var command = message();
        deliver(command);
        var first = reply(command);
        command.put("messageId", UUID.randomUUID().toString());
        command.put("operationId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> deliver(command)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(service.findByOrderId(UUID.fromString(command.get("orderId").asString())))
                .hasValueSatisfying(s -> assertThat(s.id().toString())
                        .isEqualTo(first.get("payload").get("liquidacaoId").asString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_messages WHERE operation_id=?",
                Integer.class, UUID.fromString(command.get("operationId").asString()))).isZero();
    }

    @Test
    void replayPreservesResponseAndAppliesOneDomainChange() {
        var command = message();
        deliver(command);
        var first = reply(command);
        assertThat(first.get("type").asString()).isEqualTo("LiquidacaoConcluida");
        deliver(command);
        command.put("messageId", UUID.randomUUID().toString());
        deliver(command);
        assertThat(reply(command)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements", Integer.class)).isOne();
    }

    @Test
    void missingDecisionRemainsAbsentInAllLookups() {
        UUID id = UUID.randomUUID();
        assertThat(service.findByOperationId(id)).isEmpty();
        assertThat(service.findByOrderId(id)).isEmpty();
        assertThat(service.findBySagaId(id)).isEmpty();
        assertThatNullPointerException().isThrownBy(() -> service.findByOrderId(null));
    }

    @Test
    void readsTheSameDurableDecisionByAllCorrelationKeys() {
        var command = command();
        UUID id = seed(command);
        var result = service.findByOperationId(command.operationId()).orElseThrow();
        assertThat(result.id()).isEqualTo(id);
        assertThat(result.command()).isEqualTo(command);
        assertThat(result.confirmedAt()).isNotNull();
        assertThat(service.findByOrderId(command.balance().orderId())).contains(result);
        assertThat(service.findBySagaId(command.balance().sagaId())).contains(result);
    }

    @Test
    void databaseRejectsNonConfirmedStatusAndInconsistentAmount() {
        var command = command();
        seed(command);
        assertThatThrownBy(() -> jdbc.update("UPDATE settlements SET status = 'REJECTED'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE settlements SET amount = 29"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE settlements SET quantity = 0"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(service.findByOperationId(command.operationId())).isPresent();
    }

    @Test
    void confirmsOnlyOnceAndRetainsOriginalTimestampOnReplay() {
        var command = command();
        var first = service.confirm(command);
        assertThat(service.confirm(command)).isEqualTo(first);
        assertThat(service.findByOrderId(command.balance().orderId())).contains(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM settlements", String.class)).isEqualTo("CONFIRMED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"operation", "order", "saga", "balance", "assetReservation", "buyer", "asset", "quantity", "price", "catalog"})
    void rejectsChangedPayloadOrCorrelationWithoutChangingDecision(String field) {
        var original = command();
        var first = service.confirm(original);
        UUID order = UUID.randomUUID();
        UUID saga = UUID.randomUUID();
        var different = new ConfirmSettlementCommand(UUID.randomUUID(),
                new ReservedBalance(UUID.randomUUID(), order, saga, "other", new BigDecimal("80")),
                new ReservedAsset(UUID.randomUUID(), order, saga, "other", "other-asset", 4),
                new BigDecimal("20"), "catalog-v2");
        var changed = replaceField(original, different, field);
        assertThat(changed).isNotEqualTo(original);
        assertThatThrownBy(() -> service.confirm(changed)).isInstanceOf(SettlementConflictException.class);
        assertThat(service.findByOperationId(original.operationId())).contains(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements", Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"operation", "order", "saga", "balance", "assetReservation"})
    void eachUniqueKeyIndependentlyPreventsASecondDecision(String key) {
        var original = command();
        var first = service.confirm(original);
        var conflicting = replaceField(command(), original, key);
        assertThatThrownBy(() -> service.confirm(conflicting)).isInstanceOf(SettlementConflictException.class);
        assertThat(service.findByOperationId(original.operationId())).contains(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicatesReturnTheSameDecision() throws Exception {
        var command = command();
        var results = concurrently(java.util.Collections.nCopies(8, command));
        assertThat(results).allMatch(Settlement.class::isInstance);
        assertThat(results).containsOnly(results.get(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements", Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"operation", "order", "saga", "balance", "assetReservation"})
    void concurrentConflictsHaveExactlyOneWinner(String key) throws Exception {
        var first = command();
        var second = replaceField(command(), first, key);
        var results = concurrently(List.of(first, second));
        assertThat(results.stream().filter(Settlement.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(SettlementConflictException.class::isInstance)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements", Long.class)).isEqualTo(1);
    }

    @Test
    void unrelatedOrdersCanCommitConcurrently() throws Exception {
        var results = concurrently(List.of(command(), command(), command(), command()));
        assertThat(results).allMatch(Settlement.class::isInstance);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements", Long.class)).isEqualTo(4);
    }

    @Test
    void rollbackLeavesNoDecisionAndRetryCanSucceed() {
        var command = command();
        var tx = new TransactionTemplate(transactionManager);
        assertThatIllegalStateException().isThrownBy(() -> tx.executeWithoutResult(status -> {
            service.confirm(command);
            throw new IllegalStateException("simulated failure before commit");
        }));
        assertThat(service.findByOperationId(command.operationId())).isEmpty();
        assertThat(service.confirm(command)).isNotNull();
    }

    @Test
    void lostResponseCanBeRecoveredUsingANewServiceInstance() {
        var command = command();
        var committed = service.confirm(command);
        var fresh = new SettlementService(new SettlementRepository(jdbc));
        var tx = new TransactionTemplate(transactionManager);
        var recovered = tx.execute(status -> fresh.findBySagaId(command.balance().sagaId()));
        var replay = tx.execute(status -> fresh.confirm(command));
        assertThat(recovered).contains(committed);
        assertThat(replay).isEqualTo(committed);
    }

    @Test
    void databaseFailuresRemainTechnicalAndRollBack() {
        jdbc.execute("""
                CREATE FUNCTION reject_settlement() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected persistence failure'; END $$
                """);
        jdbc.execute("CREATE TRIGGER reject_settlement AFTER INSERT ON settlements FOR EACH ROW EXECUTE FUNCTION reject_settlement()");
        var command = command();
        try {
            assertThatThrownBy(() -> service.confirm(command))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .isNotInstanceOf(SettlementConflictException.class);
            assertThat(service.findByOrderId(command.balance().orderId())).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER reject_settlement ON settlements");
            jdbc.execute("DROP FUNCTION reject_settlement()");
        }
        assertThat(service.confirm(command)).isNotNull();
    }

    private List<Object> concurrently(List<ConfirmSettlementCommand> commands) throws Exception {
        var executor = Executors.newFixedThreadPool(commands.size());
        var gate = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<Object>>();
            for (var command : commands) {
                futures.add(executor.submit(() -> {
                    gate.await();
                    try { return service.confirm(command); }
                    catch (SettlementConflictException conflict) { return conflict; }
                }));
            }
            gate.countDown();
            var results = new ArrayList<Object>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ConfirmSettlementCommand replaceField(ConfirmSettlementCommand base, ConfirmSettlementCommand source, String field) {
        UUID operation = field.equals("operation") ? source.operationId() : base.operationId();
        UUID order = field.equals("order") ? source.balance().orderId() : base.balance().orderId();
        UUID saga = field.equals("saga") ? source.balance().sagaId() : base.balance().sagaId();
        UUID balanceId = field.equals("balance") ? source.balance().reservationId() : base.balance().reservationId();
        UUID assetId = field.equals("assetReservation") ? source.asset().reservationId() : base.asset().reservationId();
        String buyer = field.equals("buyer") ? source.balance().buyerId() : base.balance().buyerId();
        String asset = field.equals("asset") ? source.asset().assetId() : base.asset().assetId();
        int quantity = field.equals("quantity") ? source.asset().quantity() : base.asset().quantity();
        BigDecimal price = field.equals("price") ? source.unitPrice() : base.unitPrice();
        String catalog = field.equals("catalog") ? source.catalogVersion() : base.catalogVersion();
        return new ConfirmSettlementCommand(operation,
                new ReservedBalance(balanceId, order, saga, buyer, price.multiply(BigDecimal.valueOf(quantity))),
                new ReservedAsset(assetId, order, saga, buyer, asset, quantity), price, catalog);
    }

    private ConfirmSettlementCommand command() {
        UUID order = UUID.randomUUID();
        UUID saga = UUID.randomUUID();
        return new ConfirmSettlementCommand(UUID.randomUUID(),
                new ReservedBalance(UUID.randomUUID(), order, saga, "buyer", new BigDecimal("30")),
                new ReservedAsset(UUID.randomUUID(), order, saga, "buyer", "asset", 3),
                BigDecimal.TEN, "catalog-v1");
    }

    private UUID seed(ConfirmSettlementCommand command) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlement_decisions
                    (order_id,saga_id,operation_id,balance_reservation_id,asset_reservation_id,outcome)
                VALUES (?,?,?,?,?,'CONFIRMED')
                """, command.balance().orderId(), command.balance().sagaId(), command.operationId(),
                command.balance().reservationId(), command.asset().reservationId());
        jdbc.update("""
                INSERT INTO settlements (settlement_id, operation_id, order_id, saga_id,
                    balance_reservation_id, asset_reservation_id, buyer_id, asset_id,
                    quantity, unit_price, amount, catalog_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, command.operationId(), command.balance().orderId(), command.balance().sagaId(),
                command.balance().reservationId(), command.asset().reservationId(), command.balance().buyerId(),
                command.asset().assetId(), command.asset().quantity(), command.unitPrice(),
                command.balance().amount(), command.catalogVersion());
        return id;
    }
}
