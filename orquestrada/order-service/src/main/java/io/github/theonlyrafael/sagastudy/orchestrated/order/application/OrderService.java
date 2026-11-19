package io.github.theonlyrafael.sagastudy.orchestrated.order.application;

import io.github.theonlyrafael.sagastudy.orchestrated.order.domain.*;
import io.github.theonlyrafael.sagastudy.orchestrated.order.repository.*;

import io.github.theonlyrafael.sagastudy.orchestrated.order.messaging.OrderReceived;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
public class OrderService {
    private final OrderRepository orders;
    private final CatalogRepository catalog;
    private final IdempotencyRepository keys;

    private final OutboxRepository outbox;

    public OrderService(
            OrderRepository orders, CatalogRepository catalog, IdempotencyRepository keys,
            OutboxRepository outbox) {
        this.outbox = outbox;
        this.orders = orders;
        this.catalog = catalog;
        this.keys = keys;
    }

    @Transactional(readOnly = true)
    public Order find(UUID id) {
        return orders.find(Objects.requireNonNull(id)).orElseThrow(OrderNotFoundException::new);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Order create(CreateOrderCommand command) {
        Objects.requireNonNull(command);
        UUID candidate = UUID.randomUUID();
        UUID claimed = keys.claim(command, candidate);
        if (!claimed.equals(candidate)) return find(claimed);
        var asset = catalog.find(command.ativoId()).orElseThrow(AssetNotFoundException::new);
        orders.insert(candidate, UUID.randomUUID(), command, asset);
        var created = find(candidate);
        outbox.append(OrderReceived.from(created));
        return created;
    }
}
