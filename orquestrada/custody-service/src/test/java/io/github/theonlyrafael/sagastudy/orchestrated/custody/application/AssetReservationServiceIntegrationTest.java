package io.github.theonlyrafael.sagastudy.orchestrated.custody.application;

import io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationException;
import io.github.theonlyrafael.sagastudy.orchestrated.custody.repository.AssetReservationRepository;
import io.github.theonlyrafael.sagastudy.orchestrated.custody.repository.PositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationException.Reason.INSUFFICIENT_ASSETS;
import static io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationException.Reason.INVALID_TRANSITION;
import static io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationException.Reason.OPERATION_CONFLICT;
import static io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationException.Reason.POSITION_NOT_FOUND;
import static io.github.theonlyrafael.sagastudy.orchestrated.custody.domain.AssetReservationException.Reason.RESERVATION_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "custody.counterparty-id=SELLER",
        "spring.rabbitmq.username=unused",
        "spring.rabbitmq.password=unused",
        "spring.rabbitmq.dynamic=false",
        "management.health.rabbit.enabled=false",
        "management.endpoint.health.group.readiness.include=db"
})
class AssetReservationServiceIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.11-bookworm@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5")
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("custody_test").withUsername("custody_test").withPassword("custody_test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private AssetReservationService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PositionRepository positions;
    @Autowired private AssetReservationRepository reservations;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM asset_reservations");
        jdbc.update("DELETE FROM positions");
        seedPosition("SELLER", "ASSET", 10, 0);
    }

    @Test
    void appliesBothMigrationsAndEnforcesPositionConstraints() {
        assertEquals(2L, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version IN ('1', '2') AND success", Long.class));
        assertThrows(DataIntegrityViolationException.class, () -> seedPosition("SELLER", "ASSET", 1, 0));
        assertThrows(DataIntegrityViolationException.class, () -> seedPosition("OTHER", "ASSET", -1, 0));
        assertThrows(DataIntegrityViolationException.class, () -> seedPosition("OTHER", "ASSET", 0, -1));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10})
    void reservesAvailableStockAndPersistsOriginalPosition(int quantity) {
        UUID sourceId = positionId("SELLER", "ASSET");
        UUID id = service.reserve(command(quantity));
        assertPosition("SELLER", "ASSET", 10 - quantity, quantity);
        assertState(id, "RESERVED");
        assertEquals(sourceId, jdbc.queryForObject(
                "SELECT source_position_id FROM asset_reservations WHERE reservation_id=?", UUID.class, id));
        assertEquals(1L, reservationCount());
        assertNoBuyerPosition();
    }

    @Test
    void insufficientStockLeavesNoPartialReservation() {
        assertReason(INSUFFICIENT_ASSETS, () -> service.reserve(command(11)));
        assertPosition("SELLER", "ASSET", 10, 0);
        assertEquals(0L, reservationCount());
    }

    @Test
    void missingAssetDoesNotCreatePosition() {
        assertReason(POSITION_NOT_FOUND, () -> service.reserve(command("BUYER", "MISSING", 1)));
        assertEquals(0L, reservationCount());
        assertPosition("SELLER", "ASSET", 10, 0);
    }

    @Test
    void counterpartyCannotBuyFromItself() {
        assertThrows(IllegalArgumentException.class, () -> service.reserve(command("SELLER", "ASSET", 1)));
        assertPosition("SELLER", "ASSET", 10, 0);
        assertEquals(0L, reservationCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESERVED", "CONFIRMED", "RELEASED"})
    void repeatedCreationReturnsOriginalEvenAfterCompletion(String status) {
        var command = command(3);
        UUID id = service.reserve(command);
        if (status.equals("CONFIRMED")) service.confirm(id);
        if (status.equals("RELEASED")) service.release(id);
        assertEquals(id, service.reserve(command));
        assertState(id, status);
        assertEquals(1L, reservationCount());
        assertPosition("SELLER", "ASSET", status.equals("RELEASED") ? 10 : 7,
                status.equals("RESERVED") ? 3 : 0);
        if (status.equals("CONFIRMED")) assertPosition("BUYER", "ASSET", 3, 0);
        else assertNoBuyerPosition();
    }

    @ParameterizedTest
    @ValueSource(strings = {"order", "saga", "buyer", "asset", "quantity"})
    void reusedOperationWithDifferentPayloadIsConflict(String changed) {
        var original = command(3);
        service.reserve(original);
        var conflicting = new ReserveAssetCommand(
                changed.equals("order") ? UUID.randomUUID() : original.orderId(),
                changed.equals("saga") ? UUID.randomUUID() : original.sagaId(), original.operationId(),
                changed.equals("buyer") ? "OTHER" : original.buyerId(),
                changed.equals("asset") ? "OTHER" : original.assetId(),
                changed.equals("quantity") ? 4 : original.quantity());
        assertReason(OPERATION_CONFLICT, () -> service.reserve(conflicting));
        assertPosition("SELLER", "ASSET", 7, 3);
        assertEquals(1L, reservationCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void orderAndSagaAreIndependentlyUnique(boolean sameOrder) {
        var original = command(3);
        service.reserve(original);
        var conflicting = new ReserveAssetCommand(
                sameOrder ? original.orderId() : UUID.randomUUID(),
                sameOrder ? UUID.randomUUID() : original.sagaId(), UUID.randomUUID(), "BUYER", "ASSET", 3);
        assertReason(OPERATION_CONFLICT, () -> service.reserve(conflicting));
        assertPosition("SELLER", "ASSET", 7, 3);
        assertEquals(1L, reservationCount());
    }

    @Test
    void confirmationCreatesBuyerPositionAndTransfersOnlyOnce() {
        UUID id = service.reserve(command(3));
        service.confirm(id);
        service.confirm(id);
        assertPosition("SELLER", "ASSET", 7, 0);
        assertPosition("BUYER", "ASSET", 3, 0);
        assertState(id, "CONFIRMED");
    }

    @Test
    void confirmationAddsToExistingBuyerWithoutChangingItsIdentityOrReservations() {
        seedPosition("BUYER", "ASSET", 5, 2);
        UUID buyerId = positionId("BUYER", "ASSET");
        UUID id = service.reserve(command(3));
        service.confirm(id);
        assertPosition("BUYER", "ASSET", 8, 2);
        assertPosition("SELLER", "ASSET", 7, 0);
        assertEquals(buyerId, positionId("BUYER", "ASSET"));
    }

    @Test
    void releaseReturnsOnlyOriginalQuantityWithoutAffectingOtherReservation() {
        UUID first = service.reserve(command(3));
        UUID second = service.reserve(command(2));
        service.release(first);
        service.release(first);
        assertPosition("SELLER", "ASSET", 8, 2);
        assertState(first, "RELEASED");
        assertState(second, "RESERVED");
        assertNoBuyerPosition();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void incompatibleTerminalTransitionIsRejected(boolean confirmFirst) {
        UUID id = service.reserve(command(3));
        if (confirmFirst) {
            service.confirm(id);
            assertReason(INVALID_TRANSITION, () -> service.release(id));
            assertPosition("SELLER", "ASSET", 7, 0);
            assertPosition("BUYER", "ASSET", 3, 0);
            assertState(id, "CONFIRMED");
        } else {
            service.release(id);
            assertReason(INVALID_TRANSITION, () -> service.confirm(id));
            assertPosition("SELLER", "ASSET", 10, 0);
            assertNoBuyerPosition();
            assertState(id, "RELEASED");
        }
    }

    @Test
    void cannotConfirmOrReleaseAbsentReservation() {
        assertReason(RESERVATION_NOT_FOUND, () -> service.confirm(UUID.randomUUID()));
        assertReason(RESERVATION_NOT_FOUND, () -> service.release(UUID.randomUUID()));
        assertPosition("SELLER", "ASSET", 10, 0);
        assertNoBuyerPosition();
    }

    @Test
    void requiresArgumentsAndValidCounterpartyConfiguration() {
        assertThrows(NullPointerException.class, () -> service.reserve(null));
        assertThrows(NullPointerException.class, () -> service.confirm(null));
        assertThrows(NullPointerException.class, () -> service.release(null));
        assertThrows(IllegalArgumentException.class, () -> new AssetReservationService(positions, reservations, " "));
        assertThrows(IllegalArgumentException.class, () -> new AssetReservationService(positions, reservations, null));
    }

    @Test
    void failedInsertRollsBackStockWithoutBecomingBusinessRefusal() {
        jdbc.execute("ALTER TABLE asset_reservations ADD CONSTRAINT test_insert_failure CHECK (quantity <> 3)");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> service.reserve(command(3)));
            assertPosition("SELLER", "ASSET", 10, 0);
            assertEquals(0L, reservationCount());
        } finally {
            jdbc.execute("ALTER TABLE asset_reservations DROP CONSTRAINT test_insert_failure");
        }
    }

    @Test
    void failedBuyerCreditRollsBackConsumptionAndKeepsReservationActive() {
        seedPosition("BUYER", "ASSET", Long.MAX_VALUE, 0);
        UUID id = service.reserve(command(3));
        assertThrows(DataIntegrityViolationException.class, () -> service.confirm(id));
        assertPosition("SELLER", "ASSET", 7, 3);
        assertPosition("BUYER", "ASSET", Long.MAX_VALUE, 0);
        assertState(id, "RESERVED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedTerminalStateSaveRollsBackAllPositionChanges(boolean confirm) {
        UUID id = service.reserve(command(3));
        String terminal = confirm ? "CONFIRMED" : "RELEASED";
        jdbc.execute("ALTER TABLE asset_reservations ADD CONSTRAINT test_terminal_failure CHECK (status <> '" + terminal + "')");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> {
                if (confirm) service.confirm(id); else service.release(id);
            });
            assertPosition("SELLER", "ASSET", 7, 3);
            assertState(id, "RESERVED");
            assertNoBuyerPosition();
        } finally {
            jdbc.execute("ALTER TABLE asset_reservations DROP CONSTRAINT test_terminal_failure");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void completionUsesPersistedSourceEvenIfConfiguredCounterpartyChanges(boolean confirm) {
        var command = command(3);
        UUID id = service.reserve(command);
        seedPosition("OTHER", "ASSET", 10, 0);
        var changedService = new AssetReservationService(positions, reservations, "OTHER");
        // Explicit transaction supplies the boundary normally supplied by the Spring service proxy.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            if (confirm) changedService.confirm(id); else changedService.release(id);
            assertEquals(id, changedService.reserve(command));
        });
        assertPosition("OTHER", "ASSET", 10, 0);
        assertPosition("SELLER", "ASSET", confirm ? 7 : 10, 0);
        assertState(id, confirm ? "CONFIRMED" : "RELEASED");
        if (confirm) assertPosition("BUYER", "ASSET", 3, 0); else assertNoBuyerPosition();
    }

    @Test
    void concurrentReservationsCannotOverdrawStock() throws Exception {
        var results = concurrently(
                () -> reserveOrReject(command(8), INSUFFICIENT_ASSETS),
                () -> reserveOrReject(command(8), INSUFFICIENT_ASSETS));
        assertEquals(1L, results.stream().filter(Boolean::booleanValue).count());
        assertPosition("SELLER", "ASSET", 2, 8);
        assertEquals(1L, reservationCount());
    }

    @Test
    void concurrentDuplicateCreationReturnsSameReservation() throws Exception {
        var command = command(3);
        var results = concurrently(() -> service.reserve(command), () -> service.reserve(command));
        assertEquals(results.get(0), results.get(1));
        assertPosition("SELLER", "ASSET", 7, 3);
        assertEquals(1L, reservationCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentDuplicateCompletionMovesStockOnce(boolean confirm) throws Exception {
        UUID id = service.reserve(command(3));
        Callable<Boolean> complete = () -> { if (confirm) service.confirm(id); else service.release(id); return true; };
        concurrently(complete, complete);
        assertPosition("SELLER", "ASSET", confirm ? 7 : 10, 0);
        assertState(id, confirm ? "CONFIRMED" : "RELEASED");
        if (confirm) assertPosition("BUYER", "ASSET", 3, 0); else assertNoBuyerPosition();
    }

    @Test
    void concurrentConfirmationAndReleaseHaveOneWinner() throws Exception {
        UUID id = service.reserve(command(3));
        var results = concurrently(
                () -> completeOrRejectConflict(() -> service.confirm(id)),
                () -> completeOrRejectConflict(() -> service.release(id)));
        assertEquals(1L, results.stream().filter(Boolean::booleanValue).count());
        boolean confirmed = results.get(0);
        assertPosition("SELLER", "ASSET", confirmed ? 7 : 10, 0);
        assertState(id, confirmed ? "CONFIRMED" : "RELEASED");
        if (confirmed) assertPosition("BUYER", "ASSET", 3, 0); else assertNoBuyerPosition();
    }

    @Test
    void concurrentDistinctTransfersAccumulateInOneBuyerPosition() throws Exception {
        UUID first = service.reserve(command(3));
        UUID second = service.reserve(command(2));
        concurrently(() -> { service.confirm(first); return true; }, () -> { service.confirm(second); return true; });
        assertPosition("SELLER", "ASSET", 5, 0);
        assertPosition("BUYER", "ASSET", 5, 0);
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM positions WHERE owner_id='BUYER'", Long.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"order", "saga", "operation"})
    void concurrentIdentifierCollisionAcrossAssetsRollsBackLoser(String shared) throws Exception {
        seedPosition("SELLER", "OTHER", 10, 0);
        var first = command(3);
        var second = new ReserveAssetCommand(
                shared.equals("order") ? first.orderId() : UUID.randomUUID(),
                shared.equals("saga") ? first.sagaId() : UUID.randomUUID(),
                shared.equals("operation") ? first.operationId() : UUID.randomUUID(), "BUYER", "OTHER", 3);
        var results = concurrently(() -> reserveOrReject(first, OPERATION_CONFLICT), () -> reserveOrReject(second, OPERATION_CONFLICT));
        assertEquals(1L, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1L, reservationCount());
        assertPosition("SELLER", "ASSET", results.get(0) ? 7 : 10, results.get(0) ? 3 : 0);
        assertPosition("SELLER", "OTHER", results.get(1) ? 7 : 10, results.get(1) ? 3 : 0);
    }

    private boolean reserveOrReject(ReserveAssetCommand command, AssetReservationException.Reason expected) {
        try { service.reserve(command); return true; }
        catch (AssetReservationException error) { assertEquals(expected, error.getReason()); return false; }
    }

    private boolean completeOrRejectConflict(Runnable operation) {
        try { operation.run(); return true; }
        catch (AssetReservationException error) { assertEquals(INVALID_TRANSITION, error.getReason()); return false; }
    }

    private void assertReason(AssetReservationException.Reason expected, Runnable operation) {
        assertEquals(expected, assertThrows(AssetReservationException.class, operation::run).getReason());
    }

    private void seedPosition(String owner, String asset, long available, long reserved) {
        jdbc.update("INSERT INTO positions (position_id, owner_id, asset_id, available_quantity, reserved_quantity) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), owner, asset, available, reserved);
    }

    private UUID positionId(String owner, String asset) {
        return jdbc.queryForObject("SELECT position_id FROM positions WHERE owner_id=? AND asset_id=?", UUID.class, owner, asset);
    }

    private void assertPosition(String owner, String asset, long available, long reserved) {
        var row = jdbc.queryForMap("SELECT available_quantity, reserved_quantity FROM positions WHERE owner_id=? AND asset_id=?", owner, asset);
        assertEquals(available, ((Number) row.get("available_quantity")).longValue());
        assertEquals(reserved, ((Number) row.get("reserved_quantity")).longValue());
    }

    private void assertState(UUID id, String expected) {
        var row = jdbc.queryForMap("SELECT status, created_at IS NOT NULL AS created, completed_at IS NOT NULL AS completed FROM asset_reservations WHERE reservation_id=?", id);
        assertEquals(expected, row.get("status"));
        assertEquals(true, row.get("created"));
        assertEquals(!expected.equals("RESERVED"), row.get("completed"));
    }

    private void assertNoBuyerPosition() {
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM positions WHERE owner_id='BUYER'", Long.class));
    }

    private long reservationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM asset_reservations", Long.class);
    }

    private ReserveAssetCommand command(int quantity) { return command("BUYER", "ASSET", quantity); }

    private ReserveAssetCommand command(String buyer, String asset, int quantity) {
        return new ReserveAssetCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), buyer, asset, quantity);
    }

    private <T> List<T> concurrently(Callable<T> first, Callable<T> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { ready.countDown(); start.await(); return first.call(); });
            var b = executor.submit(() -> { ready.countDown(); start.await(); return second.call(); });
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Both workers must be ready");
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
