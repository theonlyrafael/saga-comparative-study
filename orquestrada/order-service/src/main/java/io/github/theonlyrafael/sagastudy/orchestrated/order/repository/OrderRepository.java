package io.github.theonlyrafael.sagastudy.orchestrated.order.repository;

import io.github.theonlyrafael.sagastudy.orchestrated.order.domain.*;
import io.github.theonlyrafael.sagastudy.orchestrated.order.application.CreateOrderCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class OrderRepository {
    private final JdbcTemplate jdbc;
    public OrderRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<Order> find(UUID id) {
        return jdbc.query("SELECT * FROM orders WHERE order_id = ?", (r, n) -> new Order(
                r.getObject("order_id", UUID.class), r.getObject("saga_id", UUID.class),
                r.getString("cliente_id"), r.getString("ativo_id"), r.getInt("quantidade"),
                r.getBigDecimal("unit_price"), r.getBigDecimal("total"), r.getString("catalog_version"),
                OrderStatus.valueOf(r.getString("status")), r.getTimestamp("created_at").toInstant()), id)
                .stream().findFirst();
    }
    public void insert(UUID id, UUID saga, CreateOrderCommand command, CatalogAsset asset) {
        jdbc.update("""
                INSERT INTO orders (order_id, saga_id, cliente_id, ativo_id, quantidade,
                    unit_price, total, catalog_version, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RECEBIDA')
                """, id, saga, command.clienteId(), command.ativoId(), command.quantidade(),
                asset.unitPrice(), asset.total(command.quantidade()), asset.version());
    }
}
