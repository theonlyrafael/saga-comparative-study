package io.github.theonlyrafael.sagastudy.orchestrated.settlement.application;

import io.github.theonlyrafael.sagastudy.orchestrated.settlement.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        "spring.rabbitmq.dynamic=false", "management.health.rabbit.enabled=false",
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
