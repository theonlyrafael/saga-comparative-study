package io.github.theonlyrafael.sagastudy.orchestrated.account.application;

import io.github.theonlyrafael.sagastudy.orchestrated.account.domain.BalanceReservationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
