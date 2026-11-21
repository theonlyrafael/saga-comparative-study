package io.github.theonlyrafael.sagastudy.orchestrated.account.application;

import io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservationException;
import org.junit.jupiter.api.BeforeEach;
import io.github.theonlyrafael.sagastudy.orchestrated.messaging.ParticipantConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import io.github.theonlyrafael.sagastudy.orchestrated.account.repository.AccountRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservationException.Reason.ACCOUNT_NOT_FOUND;
import static io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservationException.Reason.INSUFFICIENT_BALANCE;
import static io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservationException.Reason.INVALID_TRANSITION;
import static io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservationException.Reason.OPERATION_CONFLICT;
import static io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservationException.Reason.RESERVATION_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.rabbitmq.username=unused",
                "spring.rabbitmq.password=unused",
                "spring.rabbitmq.dynamic=false",
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "outbox.publisher.enabled=false",
                "management.health.rabbit.enabled=false",
                "management.endpoint.health.group.readiness.include=db"
        }
)
class BalanceReservationServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(
                    "postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("account_test")
                    .withUsername("account_test")
                    .withPassword("account_test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private BalanceReservationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void prepareAccount() {
        jdbc.update("DELETE FROM balance_reservations");
        jdbc.update("DELETE FROM accounts");

        jdbc.update("""
                INSERT INTO accounts (
                    account_id,
                    available_balance,
                    reserved_balance,
                    total_consumed
                )
                VALUES (?, ?, ?, ?)
                """,
                "CLI001",
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
    }

    @Autowired ParticipantConsumer consumer;
    @Autowired ObjectMapper json;

    private ObjectNode message() {
        var command = json.createObjectNode();
        command.put("type", "ReservarSaldo");
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void correlatesTerminalCommandAndAppliesItOnce(boolean confirm) {
        var reserve = message();
        deliver(reserve);
        var reservation = reply(reserve).get("payload").get("reservaSaldoId").asString();
        var terminal = reserve.deepCopy();
        terminal.put("messageId", UUID.randomUUID().toString());
        terminal.put("operationId", UUID.randomUUID().toString());
        terminal.put("type", (confirm ? "Confirmar" : "Desfazer") + "ReservaSaldo");
        var payload = (ObjectNode) terminal.get("payload");
        payload.put("reservaSaldoId", reservation);
        if (confirm) payload.put("liquidacaoId", UUID.randomUUID().toString());
        var wrongOrder = terminal.deepCopy();
        wrongOrder.put("orderId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> deliver(wrongOrder)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        deliver(terminal);
        deliver(terminal);
        assertThat(reply(terminal).get("type").asString())
                .isEqualTo(confirm ? "SaldoConfirmado" : "ReservaSaldoDesfeita");
        assertBalances(confirm ? "80.00" : "100.00", "0.00", confirm ? "20.00" : "0.00");
    }

    @Test
    void replayMessageIdCannotBeReusedForAnotherOperation() {
        var original = message();
        deliver(original);
        var retryId = UUID.randomUUID().toString();
        deliver(original.deepCopy().put("messageId", retryId));
        var unrelated = message().put("messageId", retryId);
        assertThatThrownBy(() -> deliver(unrelated)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(reservationCount()).isOne();
        assertBalances("80.00", "20.00", "0.00");
    }

    @Test
    void concurrentDeliveriesOfOneOperationReserveOnce() throws Exception {
        var original = message();
        var retry = original.deepCopy().put("messageId", UUID.randomUUID().toString());
        concurrently(() -> { deliver(original); return true; }, () -> { deliver(retry); return true; });
        assertBalances("80.00", "20.00", "0.00");
        assertThat(reservationCount()).isOne();
        assertThat(reply(original)).isEqualTo(reply(retry));
    }

    @Test
    void failedOutboxWriteRollsBackTheClaimAndBalance() {
        var command = message();
        jdbc.execute("ALTER TABLE outbox_messages ADD CONSTRAINT inject_failure CHECK (false) NOT VALID");
        try {
            assertThatThrownBy(() -> deliver(command)).isInstanceOf(DataIntegrityViolationException.class);
            assertBalances("100.00", "0.00", "0.00");
            assertThat(reservationCount()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_messages WHERE operation_id=?",
                    Integer.class, UUID.fromString(command.get("operationId").asString()))).isZero();
        } finally {
            jdbc.execute("ALTER TABLE outbox_messages DROP CONSTRAINT inject_failure");
        }
        deliver(command);
        assertBalances("80.00", "20.00", "0.00");
    }

    @Test
    void replaysRefusalEvenAfterResourceBecomesAvailable() {
        var command = message();
        jdbc.update("UPDATE accounts SET available_balance=0");
        deliver(command);
        var refused = reply(command);
        assertThat(refused.get("payload").get("motivo").asString()).isEqualTo("SALDO_INSUFICIENTE");
        jdbc.update("UPDATE accounts SET available_balance=100");
        deliver(command);
        assertThat(reply(command)).isEqualTo(refused);
        assertThat(reservationCount()).isZero();
    }

    @Test
    void replayPreservesResponseAndAppliesOneDomainChange() {
        var command = message();
        deliver(command);
        var first = reply(command);
        assertThat(first.get("type").asString()).isEqualTo("SaldoReservado");
        deliver(command);
        command.put("messageId", UUID.randomUUID().toString());
        deliver(command);
        assertThat(reply(command)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM balance_reservations", Integer.class)).isOne();
        assertBalances("80.00", "20.00", "0.00");
    }

    @Test
    void reservesBalanceAndPersistsReservation() {
        UUID reservationId = service.reserve(command("30.00"));

        assertBalances("70.00", "30.00", "0.00");
        assertStatus(reservationId, "RESERVED");
        assertEquals(1L, reservationCount());
    }

    @Test
    void repeatedReservationReturnsOriginalWithoutMovingBalanceAgain() {
        ReserveBalanceCommand command = command("30.00");

        UUID first = service.reserve(command);
        UUID second = service.reserve(command);

        assertEquals(first, second);
        assertBalances("70.00", "30.00", "0.00");
        assertEquals(1L, reservationCount());
    }

    @Test
    void sameOperationWithDifferentPayloadIsRejected() {
        ReserveBalanceCommand original = command("30.00");
        service.reserve(original);

        ReserveBalanceCommand conflicting = new ReserveBalanceCommand(
                original.orderId(),
                original.sagaId(),
                original.operationId(),
                original.accountId(),
                new BigDecimal("40.00")
        );

        BalanceReservationException exception = assertThrows(
                BalanceReservationException.class,
                () -> service.reserve(conflicting)
        );

        assertEquals(OPERATION_CONFLICT, exception.getReason());
        assertBalances("70.00", "30.00", "0.00");
        assertEquals(1L, reservationCount());
    }

    @Test
    void sameOrderAndSagaCannotCreateAnotherReservation() {
        ReserveBalanceCommand original = command("30.00");
        service.reserve(original);

        ReserveBalanceCommand conflicting = new ReserveBalanceCommand(
                original.orderId(),
                original.sagaId(),
                UUID.randomUUID(),
                original.accountId(),
                original.amount()
        );

        BalanceReservationException exception = assertThrows(
                BalanceReservationException.class,
                () -> service.reserve(conflicting)
        );

        assertEquals(OPERATION_CONFLICT, exception.getReason());
        assertBalances("70.00", "30.00", "0.00");
        assertEquals(1L, reservationCount());
    }

    @Test
    void insufficientBalanceDoesNotCreatePartialChanges() {
        BalanceReservationException exception = assertThrows(
                BalanceReservationException.class,
                () -> service.reserve(command("101.00"))
        );

        assertEquals(INSUFFICIENT_BALANCE, exception.getReason());
        assertBalances("100.00", "0.00", "0.00");
        assertEquals(0L, reservationCount());
    }

    @Test
    void missingAccountIsRejected() {
        ReserveBalanceCommand command = new ReserveBalanceCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "UNKNOWN",
                new BigDecimal("30.00")
        );

        BalanceReservationException exception = assertThrows(
                BalanceReservationException.class,
                () -> service.reserve(command)
        );

        assertEquals(ACCOUNT_NOT_FOUND, exception.getReason());
        assertBalances("100.00", "0.00", "0.00");
        assertEquals(0L, reservationCount());
    }

    @Test
    void confirmationConsumesReservationOnlyOnce() {
        ReserveBalanceCommand command = command("30.00");
        UUID reservationId = service.reserve(command);

        service.confirm(reservationId);
        service.confirm(reservationId);

        assertBalances("70.00", "0.00", "30.00");
        assertStatus(reservationId, "CONFIRMED");

        assertEquals(reservationId, service.reserve(command));
        assertBalances("70.00", "0.00", "30.00");
    }

    @Test
    void releaseReturnsOriginalAmountOnlyOnce() {
        ReserveBalanceCommand command = command("30.00");
        UUID reservationId = service.reserve(command);

        service.release(reservationId);
        service.release(reservationId);

        assertBalances("100.00", "0.00", "0.00");
        assertStatus(reservationId, "RELEASED");

        assertEquals(reservationId, service.reserve(command));
        assertBalances("100.00", "0.00", "0.00");
    }

    @Test
    void confirmedReservationCannotBeReleased() {
        UUID reservationId = service.reserve(command("30.00"));
        service.confirm(reservationId);

        BalanceReservationException exception = assertThrows(
                BalanceReservationException.class,
                () -> service.release(reservationId)
        );

        assertEquals(INVALID_TRANSITION, exception.getReason());
        assertBalances("70.00", "0.00", "30.00");
        assertStatus(reservationId, "CONFIRMED");
    }

    @Test
    void releasedReservationCannotBeConfirmed() {
        UUID reservationId = service.reserve(command("30.00"));
        service.release(reservationId);

        BalanceReservationException exception = assertThrows(
                BalanceReservationException.class,
                () -> service.confirm(reservationId)
        );

        assertEquals(INVALID_TRANSITION, exception.getReason());
        assertBalances("100.00", "0.00", "0.00");
        assertStatus(reservationId, "RELEASED");
    }

    @Test
    void concurrentReservationsCannotOverdrawAccount() throws Exception {
        ReserveBalanceCommand first = command("80.00");
        ReserveBalanceCommand second = command("80.00");

        List<Boolean> results = concurrently(
                () -> reserveOrRejectInsufficientBalance(first),
                () -> reserveOrRejectInsufficientBalance(second)
        );

        assertEquals(1L, results.stream().filter(Boolean::booleanValue).count());
        assertBalances("20.00", "80.00", "0.00");
        assertEquals(1L, reservationCount());
    }

    @Test
    void concurrentDuplicatesCreateOnlyOneReservation() throws Exception {
        ReserveBalanceCommand command = command("30.00");

        List<UUID> results = concurrently(
                () -> service.reserve(command),
                () -> service.reserve(command)
        );

        assertEquals(results.get(0), results.get(1));
        assertBalances("70.00", "30.00", "0.00");
        assertEquals(1L, reservationCount());
    }

    @Test
    void concurrentConfirmationsConsumeOnlyOnce() throws Exception {
        UUID reservationId = service.reserve(command("30.00"));

        concurrently(
                () -> {
                    service.confirm(reservationId);
                    return true;
                },
                () -> {
                    service.confirm(reservationId);
                    return true;
                }
        );

        assertBalances("70.00", "0.00", "30.00");
        assertStatus(reservationId, "CONFIRMED");
    }

    @Test
    void fullBalanceCanBeReservedAndReleased() {
        UUID reservationId = service.reserve(command("100.00"));
        assertBalances("0.00", "100.00", "0.00");
        service.release(reservationId);
        assertBalances("100.00", "0.00", "0.00");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void orderAndSagaAreIndependentlyUnique(boolean reuseOrder) {
        var original = command("30.00");
        service.reserve(original);
        var duplicate = new ReserveBalanceCommand(
                reuseOrder ? original.orderId() : UUID.randomUUID(),
                reuseOrder ? UUID.randomUUID() : original.sagaId(),
                UUID.randomUUID(), original.accountId(), original.amount());

        var error = assertThrows(BalanceReservationException.class,
                () -> service.reserve(duplicate));
        assertEquals(OPERATION_CONFLICT, error.getReason());
        assertBalances("70.00", "30.00", "0.00");
        assertEquals(1L, reservationCount());
    }

    @Test
    void missingReservationCannotBeConfirmedOrReleased() {
        UUID missing = UUID.randomUUID();
        assertEquals(RESERVATION_NOT_FOUND,
                assertThrows(BalanceReservationException.class,
                        () -> service.confirm(missing)).getReason());
        assertEquals(RESERVATION_NOT_FOUND,
                assertThrows(BalanceReservationException.class,
                        () -> service.release(missing)).getReason());
        assertBalances("100.00", "0.00", "0.00");
    }

    @ParameterizedTest
    @CsvSource({"reserve,0", "reserve,-1", "consume,0", "consume,-1", "release,0", "release,-1"})
    void repositoryRejectsNonPositiveMovements(String operation, BigDecimal amount) {
        jdbc.update("UPDATE accounts SET available_balance = 60, reserved_balance = 30, total_consumed = 10");
        var transaction = new TransactionTemplate(transactionManager);
        Integer changed = transaction.execute(status -> switch (operation) {
            case "reserve" -> accounts.reserve("CLI001", amount);
            case "consume" -> accounts.consume("CLI001", amount);
            case "release" -> accounts.release("CLI001", amount);
            default -> throw new IllegalArgumentException(operation);
        });
        assertEquals(0, changed);
        assertBalances("60.00", "30.00", "10.00");
    }

    @Test
    void monetaryPrimitivesRequireACallerTransaction() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> accounts.reserve("CLI001", BigDecimal.ONE));
        assertBalances("100.00", "0.00", "0.00");
    }

    @Test
    void nonexistentAccountCannotHideAnOperationConflict() {
        var original = command("30.00");
        service.reserve(original);
        var conflicting = new ReserveBalanceCommand(original.orderId(), original.sagaId(),
                original.operationId(), "MISSING", original.amount());
        assertEquals(OPERATION_CONFLICT, assertThrows(BalanceReservationException.class,
                () -> service.reserve(conflicting)).getReason());
        assertBalances("70.00", "30.00", "0.00");
        assertEquals(1L, reservationCount());
    }

    @Test
    void conflictingAccountCannotReuseOperation() {
        jdbc.update("INSERT INTO accounts (account_id, available_balance) VALUES ('CLI002', 100)");
        var original = command("30.00");
        service.reserve(original);
        var duplicate = new ReserveBalanceCommand(original.orderId(), original.sagaId(),
                original.operationId(), "CLI002", original.amount());
        assertEquals(OPERATION_CONFLICT,
                assertThrows(BalanceReservationException.class,
                        () -> service.reserve(duplicate)).getReason());
        assertBalances("70.00", "30.00", "0.00");
        assertEquals(0, new BigDecimal("100.00").compareTo(jdbc.queryForObject(
                "SELECT available_balance FROM accounts WHERE account_id = 'CLI002'", BigDecimal.class)));
        assertEquals(1L, reservationCount());
    }

    @Test
    void failedReservationInsertRollsBackBalanceAndRemainsTechnicalFailure() {
        // Inject a storage failure after the balance UPDATE, in this disposable database only.
        jdbc.execute("ALTER TABLE balance_reservations ADD CONSTRAINT test_reject_insert CHECK (amount <> 30)");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> service.reserve(command("30.00")));
            assertBalances("100.00", "0.00", "0.00");
            assertEquals(0L, reservationCount());
        } finally {
            jdbc.execute("ALTER TABLE balance_reservations DROP CONSTRAINT test_reject_insert");
        }
    }

    @Test
    void concurrentReleasesReturnMoneyOnlyOnce() throws Exception {
        UUID reservationId = service.reserve(command("30.00"));
        concurrently(
                () -> { service.release(reservationId); return true; },
                () -> { service.release(reservationId); return true; });
        assertBalances("100.00", "0.00", "0.00");
        assertStatus(reservationId, "RELEASED");
    }

    @Test
    void concurrentConfirmationAndReleaseHaveOneWinner() throws Exception {
        UUID reservationId = service.reserve(command("30.00"));
        List<Boolean> results = concurrently(
                () -> executeOrRejectTerminalConflict(() -> service.confirm(reservationId)),
                () -> executeOrRejectTerminalConflict(() -> service.release(reservationId)));
        assertEquals(1L, results.stream().filter(Boolean::booleanValue).count());
        if (results.get(0)) {
            assertBalances("70.00", "0.00", "30.00");
            assertStatus(reservationId, "CONFIRMED");
        } else {
            assertBalances("100.00", "0.00", "0.00");
            assertStatus(reservationId, "RELEASED");
        }
    }

    private boolean executeOrRejectTerminalConflict(Runnable operation) {
        try {
            operation.run();
            return true;
        } catch (BalanceReservationException error) {
            assertEquals(INVALID_TRANSITION, error.getReason());
            return false;
        }
    }

    @Test
    void invalidMonetaryValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> command("0.00"));
        assertThrows(IllegalArgumentException.class, () -> command("-1.00"));
        assertThrows(IllegalArgumentException.class, () -> command("0.001"));
        assertThrows(
                IllegalArgumentException.class,
                () -> command("100000000000000000.00")
        );

        assertBalances("100.00", "0.00", "0.00");
        assertEquals(0L, reservationCount());
    }

    private ReserveBalanceCommand command(String amount) {
        return new ReserveBalanceCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CLI001",
                new BigDecimal(amount)
        );
    }

    private boolean reserveOrRejectInsufficientBalance(
            ReserveBalanceCommand command
    ) {
        try {
            service.reserve(command);
            return true;
        } catch (BalanceReservationException exception) {
            if (exception.getReason() != INSUFFICIENT_BALANCE) {
                throw exception;
            }

            return false;
        }
    }

    private void assertBalances(
            String available,
            String reserved,
            String consumed
    ) {
        jdbc.queryForObject("""
                SELECT available_balance, reserved_balance, total_consumed
                  FROM accounts
                 WHERE account_id = ?
                """,
                (resultSet, rowNumber) -> {
                    assertEquals(
                            0,
                            new BigDecimal(available).compareTo(
                                    resultSet.getBigDecimal("available_balance")
                            )
                    );

                    assertEquals(
                            0,
                            new BigDecimal(reserved).compareTo(
                                    resultSet.getBigDecimal("reserved_balance")
                            )
                    );

                    assertEquals(
                            0,
                            new BigDecimal(consumed).compareTo(
                                    resultSet.getBigDecimal("total_consumed")
                            )
                    );

                    return true;
                },
                "CLI001"
        );
    }

    private void assertStatus(UUID reservationId, String expected) {
        String actual = jdbc.queryForObject(
                """
                SELECT status
                  FROM balance_reservations
                 WHERE reservation_id = ?
                """,
                String.class,
                reservationId
        );

        assertEquals(expected, actual);
    }

    private long reservationCount() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM balance_reservations",
                Long.class
        );

        return count.longValue();
    }

    private <T> List<T> concurrently(
            Callable<T> first,
            Callable<T> second
    ) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(2);

        try {
            var firstFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                return first.call();
            });

            var secondFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                return second.call();
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS), "Workers must be ready before the race");
            start.countDown();

            return List.of(
                    firstFuture.get(20, TimeUnit.SECONDS),
                    secondFuture.get(20, TimeUnit.SECONDS)
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
