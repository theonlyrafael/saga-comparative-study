package io.github.theonlyrafael.sagastudy.orchestrated.orchestrator;

import io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.application.SagaCoordinator;
import io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.application.SagaReconciler;
import io.github.theonlyrafael.sagastudy.orchestrated.orchestrator.repository.SagaRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "reconciliation.poll-interval=1h", "saga.retry.max-attempts=2",
        "outbox.publisher.enabled=false", "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.username=unused", "spring.rabbitmq.password=unused"
})
class SagaCoordinatorIntegrationTest {
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5")
            .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired SagaReconciler reconciler;
    @Autowired SagaCoordinator coordinator;
    @Autowired SagaRepository sagas;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    private final UUID sagaId = UUID.randomUUID();
    private ObjectNode received;

    @BeforeEach
    void prepare() {
        received = json.createObjectNode();
        received.put("schemaVersion", 1);
        received.put("type", "OrdemRecebida");
        received.put("sagaId", sagaId.toString());
        for (String field : new String[]{"orderId", "operationId", "messageId"}) {
            received.put(field, UUID.randomUUID().toString());
        }
        received.put("occurredAt", "2026-11-23T00:00:00Z");
        var snapshot = received.putObject("payload");
        snapshot.put("clienteId", "CLI001");
        snapshot.put("ativoId", "ATV002");
        snapshot.put("quantidade", 3);
        snapshot.put("precoUnitario", "25.50");
        snapshot.put("valorTotal", "76.50");
        snapshot.put("versaoCatalogo", "demo-v1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitsForBothEffectsAndForTheOrderAcknowledgement(boolean balanceFirst) {
        deliver(settlementDecision());
        assertState("EFETIVANDO");
        var balance = reply("ConfirmarReservaSaldo", "SaldoConfirmado");
        var asset = reply("ConfirmarReservaAtivo", "AtivoConfirmado");
        var first = balanceFirst ? balance : asset;
        deliver(first);
        deliver(first.deepCopy().put("messageId", UUID.randomUUID().toString()));
        assertThat(stepCount("FinalizarOrdem")).isZero();
        deliver(balanceFirst ? asset : balance);
        var finalized = reply("FinalizarOrdem", "OrdemLiquidada");
        assertThat(finalized.get("payload").get("liquidacaoId")).isEqualTo(asset.get("payload").get("liquidacaoId"));
        assertState("EFETIVANDO");
        deliver(finalized);
        deliver(finalized);
        assertState("CONCLUIDA");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM saga_steps WHERE saga_id=? AND completed_at IS NOT NULL
                """, Integer.class, sagaId)).isEqualTo(6);
    }

    @Test
    void concurrentStartsAndConfirmationsIssueEachCommandOnlyOnce() throws Exception {
        concurrently(received, received.deepCopy().put("messageId", UUID.randomUUID().toString()));
        var decision = settlementDecision();
        deliver(decision);
        concurrently(reply("ConfirmarReservaSaldo", "SaldoConfirmado"), reply("ConfirmarReservaAtivo", "AtivoConfirmado"));
        assertThat(stepCount("ReservarSaldo")).isOne();
        assertThat(stepCount("FinalizarOrdem")).isOne();
        deliver(reply("FinalizarOrdem", "OrdemLiquidada"));
        assertState("CONCLUIDA");
    }

    @Test
    void invalidCorrelationAndConflictingReplaysDoNotAdvance() {
        deliver(received);
        var reserved = reply("ReservarSaldo", "SaldoReservado");
        resource(reserved, "reservaSaldoId");
        reject(reserved.deepCopy().put("operationId", UUID.randomUUID().toString()));
        reject(reserved.deepCopy().put("sagaId", UUID.randomUUID().toString()));
        reject(reserved.deepCopy().put("orderId", UUID.randomUUID().toString()));
        reject(reserved.deepCopy().put("messageId", received.get("messageId").asString()));
        reject(received.deepCopy().put("operationId", UUID.randomUUID().toString())
                .put("messageId", UUID.randomUUID().toString()));
        assertThat(stepCount("ReservarAtivo")).isZero();
        deliver(reserved);
        var divergent = reserved.deepCopy().put("messageId", UUID.randomUUID().toString());
        resource(divergent, "reservaSaldoId");
        reject(divergent);
        var asset = reply("ReservarAtivo", "AtivoReservado");
        resource(asset, "reservaSaldoId");
        resource(asset, "reservaAtivoId");
        reject(asset);
        assertState("RESERVANDO_ATIVO");
        assertThat(stepCount("ConfirmarLiquidacao")).isZero();
    }

    @Test
    void rollsBackBothEffectsAndTheReceiptIfSecondCommandCannotBeStored() {
        var decision = settlementDecision();
        jdbc.execute("""
                ALTER TABLE outbox_messages ADD CONSTRAINT simulated_outbox_failure
                CHECK (message_type <> 'ConfirmarReservaAtivo') NOT VALID
                """);
        try {
            assertThatThrownBy(() -> deliver(decision)).isInstanceOf(DataIntegrityViolationException.class);
            assertState("CONFIRMANDO_LIQUIDACAO");
            assertThat(stepCount("ConfirmarReservaSaldo")).isZero();
            assertThat(stepCount("ConfirmarReservaAtivo")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE outbox_messages DROP CONSTRAINT simulated_outbox_failure");
        }
        deliver(decision);
        assertThat(stepCount("ConfirmarReservaSaldo")).isOne();
        assertThat(stepCount("ConfirmarReservaAtivo")).isOne();
    }

    @Test
    void rejectsOrderAfterBalanceRefusalOnlyWhenOrderAcknowledgesIt() {
        deliver(received);
        var refusal = reply("ReservarSaldo", "SaldoRecusado");
        payload(refusal).put("motivo", "SALDO_INSUFICIENTE");
        deliver(refusal);
        assertThat(stepCount("ReservarAtivo")).isZero();
        var ack = reply("FinalizarOrdem", "OrdemRejeitada");
        assertThat(payload(ack).get("motivo").asString()).isEqualTo("SALDO_INSUFICIENTE");
        var opposite = ack.deepCopy().put("type", "OrdemLiquidada");
        payload(opposite).remove("motivo");
        resource(opposite, "liquidacaoId");
        reject(opposite);
        assertState("COMPENSANDO");
        deliver(ack);
        assertState("REJEITADA");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ATIVO_INSUFICIENTE", "POSICAO_INEXISTENTE"})
    void rejectsOnlyAfterReleasingTheReservedBalance(String reason) throws Exception {
        reserveBalance();
        var refusal = reply("ReservarAtivo", "AtivoRecusado");
        payload(refusal).put("motivo", reason);
        concurrently(refusal, refusal.deepCopy().put("messageId", UUID.randomUUID().toString()));
        assertState("COMPENSANDO");
        assertThat(stepCount("ConfirmarLiquidacao")).isZero();
        assertThat(stepCount("DesfazerReservaAtivo")).isZero();
        assertThat(stepCount("FinalizarOrdem")).isZero();

        var released = reply("DesfazerReservaSaldo", "ReservaSaldoDesfeita");
        assertThat(payload(released).get("reservaSaldoId")).isEqualTo(payload(refusal).get("reservaSaldoId"));
        var wrongReservation = released.deepCopy();
        resource(wrongReservation, "reservaSaldoId");
        reject(wrongReservation);
        assertThat(stepCount("FinalizarOrdem")).isZero();

        concurrently(released, released.deepCopy().put("messageId", UUID.randomUUID().toString()));
        var ack = reply("FinalizarOrdem", "OrdemRejeitada");
        assertThat(payload(ack).get("motivo").asString()).isEqualTo(reason);
        assertState("COMPENSANDO");
        deliver(ack);
        assertState("REJEITADA");
        assertThat(stepCount("DesfazerReservaSaldo")).isOne();
        assertThat(stepCount("FinalizarOrdem")).isOne();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void releasesAssetThenBalanceAfterDurableDenial(boolean legacy) {
        var refusal = settlementDecision().put("type", "LiquidacaoFalhou");
        payload(refusal).remove("liquidacaoId");
        payload(refusal).put("motivo", "LIQUIDACAO_NEGADA");
        if (legacy) {
            recordLegacyRefusal(refusal);
            applyMigration("V3__resume_denied_settlements.sql");
        }
        deliver(refusal);
        deliver(refusal);
        assertState("COMPENSANDO");
        assertThat(stepCount("DesfazerReservaSaldo")).isZero();
        assertThat(stepCount("FinalizarOrdem")).isZero();

        var asset = reply("DesfazerReservaAtivo", "ReservaAtivoDesfeita");
        var releaseOperation = UUID.fromString(asset.get("operationId").asString());
        markPublishedAndExpired(releaseOperation);
        reconciler.reconcile();
        assertThat(jdbc.queryForObject("SELECT retry_count FROM saga_steps WHERE operation_id=?",
                Integer.class, releaseOperation)).isOne();
        assertThat(stepCount("DesfazerReservaSaldo")).isZero();
        assertThat(payload(asset).get("reservaAtivoId")).isEqualTo(payload(refusal).get("reservaAtivoId"));
        deliver(asset);
        deliver(asset);
        var balance = reply("DesfazerReservaSaldo", "ReservaSaldoDesfeita");
        assertThat(payload(balance).get("reservaSaldoId")).isEqualTo(payload(refusal).get("reservaSaldoId"));
        assertThat(stepCount("FinalizarOrdem")).isZero();
        deliver(balance);
        var ack = reply("FinalizarOrdem", "OrdemRejeitada");
        assertThat(payload(ack).get("motivo").asString()).isEqualTo("LIQUIDACAO_NEGADA");
        assertState("COMPENSANDO");
        deliver(ack);
        assertState("REJEITADA");
    }

    @Test
    void migrationResumesAnAssetRefusalAlreadyRecordedByThePreviousCoordinator() {
        reserveBalance();
        var refusal = reply("ReservarAtivo", "AtivoRecusado");
        payload(refusal).put("motivo", "ATIVO_INSUFICIENTE");
        recordLegacyRefusal(refusal);
        applyMigration("V2__resume_refused_asset_reservations.sql");
        assertState("COMPENSANDO");
        deliver(refusal);
        var released = reply("DesfazerReservaSaldo", "ReservaSaldoDesfeita");
        assertThat(payload(released).get("reservaSaldoId")).isEqualTo(payload(refusal).get("reservaSaldoId"));
        deliver(released);
        var ack = reply("FinalizarOrdem", "OrdemRejeitada");
        assertThat(payload(ack).get("motivo").asString()).isEqualTo("ATIVO_INSUFICIENTE");
        deliver(ack);
        assertState("REJEITADA");
    }

    @Test
    void reconcilesOnlyPublishedUnansweredCommandsAndKeepsTheOriginalEnvelope() {
        var decision = settlementDecision();
        var settlementOp = UUID.fromString(decision.get("operationId").asString());
        var original = jdbc.queryForMap("SELECT message_id, envelope::text FROM outbox_messages WHERE operation_id=?", settlementOp);
        expire(settlementOp);
        jdbc.update("UPDATE outbox_messages SET next_attempt_at='2099-01-01' WHERE operation_id=?", settlementOp);
        reconciler.reconcile();
        assertThat(jdbc.queryForObject("""
                SELECT next_attempt_at='2099-01-01' AND published_at IS NULL FROM outbox_messages WHERE operation_id=?
                """, Boolean.class, settlementOp)).isTrue();

        for (int attempt = 1; attempt <= 2; attempt++) {
            markPublishedAndExpired(settlementOp);
            reconciler.reconcile();
            assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox_messages WHERE operation_id=?",
                    Boolean.class, settlementOp)).isTrue();
            assertThat(jdbc.queryForMap("SELECT message_id, envelope::text FROM outbox_messages WHERE operation_id=?", settlementOp))
                    .isEqualTo(original);
            assertThat(stepCount("DesfazerReservaAtivo")).isZero();
            assertThat(stepCount("DesfazerReservaSaldo")).isZero();
        }
        assertState("RECUPERACAO_PENDENTE");
        deliver(decision);
        assertState("EFETIVANDO");

        var balance = reply("ConfirmarReservaSaldo", "SaldoConfirmado");
        var asset = reply("ConfirmarReservaAtivo", "AtivoConfirmado");
        deliver(balance);
        var balanceOp = UUID.fromString(balance.get("operationId").asString());
        var assetOp = UUID.fromString(asset.get("operationId").asString());
        markPublishedAndExpired(balanceOp);
        markPublishedAndExpired(assetOp);
        reconciler.reconcile();
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox_messages WHERE operation_id=?",
                Boolean.class, balanceOp)).isFalse();
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox_messages WHERE operation_id=?",
                Boolean.class, assetOp)).isTrue();
        assertThat(stepCount("FinalizarOrdem")).isZero();
        deliver(asset);
        deliver(reply("FinalizarOrdem", "OrdemLiquidada"));
        assertState("CONCLUIDA");
    }

    @Test
    void concurrentReconcilersWaitForTheBrokerDeadlineAndRearmOnlyOnce() throws Exception {
        deliver(received);
        var op = UUID.fromString(reply("ReservarSaldo", "SaldoReservado").get("operationId").asString());
        expire(op);
        jdbc.update("UPDATE outbox_messages SET published_at=now() WHERE operation_id=?", op);
        reconciler.reconcile();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_messages WHERE operation_id=?",
                Boolean.class, op)).isTrue();

        markPublishedAndExpired(op);
        concurrently(reconciler::reconcile, reconciler::reconcile);
        assertThat(jdbc.queryForObject("SELECT retry_count FROM saga_steps WHERE operation_id=?", Integer.class, op)).isOne();
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox_messages WHERE operation_id=?",
                Boolean.class, op)).isTrue();
        assertState("RESERVANDO_SALDO");
    }

    private void expire(UUID operation) {
        jdbc.update("UPDATE saga_steps SET wait_started_at=now()-interval '1 day' WHERE operation_id=?", operation);
    }

    private void markPublishedAndExpired(UUID operation) {
        jdbc.update("UPDATE outbox_messages SET published_at=now()-interval '1 day' WHERE operation_id=?", operation);
        expire(operation);
    }

    private void recordLegacyRefusal(ObjectNode refusal) {
        // Reproduce the committed V1 state, including the receipt that prevents redelivery from advancing it.
        var operation = UUID.fromString(refusal.get("operationId").asString());
        var content = refusal.deepCopy();
        content.remove("messageId");
        content.remove("occurredAt");
        jdbc.update("INSERT INTO inbox_operations (operation_id, saga_id, content) VALUES (?, ?, ?::jsonb)",
                operation, sagaId, content.toString());
        jdbc.update("INSERT INTO inbox_deliveries (message_id, operation_id) VALUES (?, ?)",
                UUID.fromString(refusal.get("messageId").asString()), operation);
        jdbc.update("UPDATE saga_steps SET response_type=?, completed_at=now() WHERE operation_id=?",
                refusal.get("type").asString(), operation);
        jdbc.update("UPDATE saga_instances SET state='RECUPERACAO_PENDENTE' WHERE saga_id=?", sagaId);
    }

    private void applyMigration(String name) {
        var migration = new ClassPathResource("db/migration/" + name);
        new ResourceDatabasePopulator(migration).execute(jdbc.getDataSource());
    }

    private void reserveBalance() {
        deliver(received);
        var balance = reply("ReservarSaldo", "SaldoReservado");
        resource(balance, "reservaSaldoId");
        deliver(balance);
    }

    private ObjectNode settlementDecision() {
        reserveBalance();
        var asset = reply("ReservarAtivo", "AtivoReservado");
        resource(asset, "reservaAtivoId");
        deliver(asset);
        var decision = reply("ConfirmarLiquidacao", "LiquidacaoConcluida");
        resource(decision, "liquidacaoId");
        return decision;
    }

    private ObjectNode reply(String command, String response) {
        var body = jdbc.queryForObject("""
                SELECT o.envelope::text FROM outbox_messages o JOIN saga_steps s USING (operation_id)
                WHERE s.saga_id=? AND s.command_type=?
                """, String.class, sagaId, command);
        var reply = (ObjectNode) json.readTree(body);
        reply.put("type", response);
        reply.put("messageId", UUID.randomUUID().toString());
        if (command.equals("FinalizarOrdem")) payload(reply).remove("estado");
        return reply;
    }

    private int stepCount(String command) {
        return jdbc.queryForObject("SELECT count(*) FROM saga_steps WHERE saga_id=? AND command_type=?",
                Integer.class, sagaId, command);
    }

    private void assertState(String expected) {
        assertThat(sagas.find(sagaId).orElseThrow().estado().name()).isEqualTo(expected);
    }

    private static ObjectNode payload(ObjectNode event) {
        return (ObjectNode) event.get("payload");
    }

    private static void resource(ObjectNode event, String field) {
        payload(event).put(field, UUID.randomUUID().toString());
    }

    private void reject(ObjectNode event) {
        assertThatThrownBy(() -> deliver(event)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    private void deliver(ObjectNode event) {
        var properties = new MessageProperties();
        properties.setReceivedRoutingKey(event.get("type").asString());
        coordinator.receive(new Message(json.writeValueAsBytes(event), properties));
    }

    private void concurrently(ObjectNode first, ObjectNode second) throws Exception {
        concurrently(() -> deliver(first), () -> deliver(second));
    }

    private void concurrently(Runnable first, Runnable second) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { gate.await(); first.run(); return null; });
            var b = pool.submit(() -> { gate.await(); second.run(); return null; });
            gate.countDown();
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
