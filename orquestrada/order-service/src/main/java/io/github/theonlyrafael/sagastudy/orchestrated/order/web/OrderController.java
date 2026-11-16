package io.github.theonlyrafael.sagastudy.orchestrated.order.web;

import io.github.theonlyrafael.sagastudy.orchestrated.order.application.*;
import io.github.theonlyrafael.sagastudy.orchestrated.order.domain.Order;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/ordens")
public class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Order> create(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateOrderRequest request) {
        var order =
                service.create(
                        new CreateOrderCommand(
                                request.clienteId(), request.ativoId(), request.quantidade(), key));
        return ResponseEntity.accepted()
                .location(URI.create("/api/ordens/" + order.orderId()))
                .body(order);
    }

    @GetMapping("/{orderId}")
    public Order find(@PathVariable UUID orderId) {
        return service.find(orderId);
    }
}
