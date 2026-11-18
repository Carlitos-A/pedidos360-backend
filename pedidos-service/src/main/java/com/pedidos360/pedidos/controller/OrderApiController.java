package com.pedidos360.pedidos.controller;

import com.pedidos360.pedidos.messaging.OrderEventPublisher;
import com.pedidos360.pedidos.model.Pedido;
import com.pedidos360.pedidos.model.PedidoItem;
import com.pedidos360.pedidos.model.PedidoStatusEvent;
import com.pedidos360.pedidos.repository.PedidoRepository;
import jakarta.transaction.Transactional;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/orders")
public class OrderApiController {
    private static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");
    private final PedidoRepository repository;
    private final OrderEventPublisher publisher;
    public OrderApiController(PedidoRepository repository, OrderEventPublisher publisher) {
        this.repository = repository; this.publisher = publisher;
    }

    public record OrderInput(String customerName, String customerId, String customerTenantId,
                             String detail, Long total, List<PedidoItem> items) {}
    public record StatusInput(String status) {}
    public record Page<T>(List<T> items, long total, int page, int pageSize) {}
    public record OrderView(String id, String customerName, String customerId, String customerTenantId,
                            String detail, Double total, String status, String createdAt,
                            String updatedAt, List<PedidoStatusEvent> history, List<PedidoItem> items) {}

    @GetMapping
    @Transactional
    public Page<OrderView> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status, @RequestParam(required = false) String q,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 100 || (from != null && to != null && from.isAfter(to))) bad();
        String wanted = status == null || status.isBlank() ? null : normalized(status);
        String search = q == null ? "" : q.strip().toLowerCase();
        List<Pedido> matches = repository.findAll().stream()
            .filter(p -> visible(p, jwt))
            .filter(p -> wanted == null || wanted.equals(normalized(p.getEstado())))
            .filter(p -> search.isEmpty() || (p.getId() + " " + p.getCliente() + " " + p.getDetalle()).toLowerCase().contains(search))
            .filter(p -> from == null || !p.getFecha().toLocalDate().isBefore(from))
            .filter(p -> to == null || !p.getFecha().toLocalDate().isAfter(to))
            .sorted(Comparator.comparing(Pedido::getFecha, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Pedido::getId, Comparator.reverseOrder())).toList();
        long start = (long) page * pageSize;
        List<OrderView> items = start >= matches.size() ? List.of() :
            matches.subList((int) start, (int) Math.min(matches.size(), start + pageSize)).stream().map(this::view).toList();
        return new Page<>(items, matches.size(), page, pageSize);
    }

    @GetMapping("/{id}")
    @Transactional
    public OrderView get(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        Pedido p = find(id);
        if (!visible(p, jwt)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return view(p);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public OrderView create(@RequestBody OrderInput input, @AuthenticationPrincipal Jwt jwt) {
        validate(input);
        Pedido p = new Pedido();
        apply(p, input);
        p.setEstado("CREATED");
        p.setFecha(java.time.LocalDateTime.now(SANTIAGO));
        p.getHistory().add(event("CREATED", jwt));
        repository.saveAndFlush(p);
        publisher.publicarPedidoCreado(p);
        return view(p);
    }

    @PutMapping("/{id}")
    @Transactional
    public OrderView update(@PathVariable Long id, @RequestBody OrderInput input) {
        validate(input);
        Pedido p = find(id);
        apply(p, input);
        p.setUpdatedAt(OffsetDateTime.now());
        repository.saveAndFlush(p);
        return view(p);
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public OrderView status(@PathVariable Long id, @RequestBody StatusInput input, @AuthenticationPrincipal Jwt jwt) {
        Pedido p = find(id);
        String current = normalized(p.getEstado());
        String next = normalized(input.status());
        Map<String, List<String>> allowed = Map.of(
            "CREATED", List.of("ACCEPTED", "CANCELLED"),
            "ACCEPTED", List.of("IN_PROGRESS", "CANCELLED"),
            "IN_PROGRESS", List.of("COMPLETED", "CANCELLED"));
        if (!allowed.getOrDefault(current, List.of()).contains(next))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transición de estado inválida");
        p.setEstado(next);
        p.setUpdatedAt(OffsetDateTime.now());
        p.getHistory().add(event(next, jwt));
        repository.saveAndFlush(p);
        return view(p);
    }

    private Pedido find(Long id) { return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)); }
    private boolean visible(Pedido p, Jwt jwt) {
        if (jwt.getClaimAsStringList("roles") != null && jwt.getClaimAsStringList("roles").contains("Admin")) return true;
        return p.getCustomerId() != null && p.getCustomerTenantId() != null
            && p.getCustomerId().equals(jwt.getClaimAsString("oid"))
            && p.getCustomerTenantId().equals(jwt.getClaimAsString("tid"));
    }
    private void apply(Pedido p, OrderInput input) {
        p.setCliente(input.customerName().strip()); p.setCustomerId(input.customerId().strip());
        p.setCustomerTenantId(input.customerTenantId().strip()); p.setDetalle(input.detail().strip());
        p.setTotal(input.total().doubleValue());
        p.getItems().clear(); if (input.items() != null) p.getItems().addAll(input.items());
    }
    private void validate(OrderInput i) {
        if (i == null || blank(i.customerName()) || blank(i.customerId()) || blank(i.customerTenantId())
            || blank(i.detail()) || i.total() == null || i.total() < 0
            || i.items() != null && i.items().stream().anyMatch(x -> x == null || blank(x.getProductId())
                || blank(x.getName()) || x.getQuantity() == null || x.getQuantity() < 1
                || x.getUnitPrice() == null || x.getUnitPrice() < 0)) bad();
    }
    private boolean blank(String s) { return s == null || s.isBlank(); }
    private void bad() { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
    private String normalized(String s) {
        if (s == null) return "UNKNOWN";
        return switch (s.trim().toUpperCase()) {
            case "PENDIENTE" -> "CREATED";
            case "EN_PREPARACION" -> "IN_PROGRESS";
            case "ENTREGADO" -> "COMPLETED";
            default -> s.trim().toUpperCase();
        };
    }
    private PedidoStatusEvent event(String status, Jwt jwt) {
        return new PedidoStatusEvent(status, OffsetDateTime.now(), jwt.getClaimAsString("name") == null ? jwt.getSubject() : jwt.getClaimAsString("name"), "Admin");
    }
    private OrderView view(Pedido p) {
        String date = p.getFecha() == null ? "" : p.getFecha().atZone(SANTIAGO).toOffsetDateTime().toString();
        return new OrderView(String.valueOf(p.getId()), p.getCliente(), p.getCustomerId(), p.getCustomerTenantId(),
            p.getDetalle(), p.getTotal(), normalized(p.getEstado()), date,
            p.getUpdatedAt() == null ? null : p.getUpdatedAt().toString(),
            List.copyOf(p.getHistory().reversed()), List.copyOf(p.getItems()));
    }
}
