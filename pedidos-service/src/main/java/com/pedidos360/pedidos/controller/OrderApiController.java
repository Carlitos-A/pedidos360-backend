package com.pedidos360.pedidos.controller;

import com.pedidos360.pedidos.messaging.OrderEventPublisher;
import com.pedidos360.pedidos.model.Pedido;
import com.pedidos360.pedidos.model.PedidoItem;
import com.pedidos360.pedidos.model.PedidoStatusEvent;
import com.pedidos360.pedidos.model.OrderStatus;
import com.pedidos360.pedidos.repository.PedidoRepository;
import com.pedidos360.pedidos.repository.AuditEventRepository;
import com.pedidos360.pedidos.model.AuditEvent;
import jakarta.transaction.Transactional;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.ArrayList;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import com.pedidos360.pedidos.identity.CustomerDirectory;
import com.pedidos360.pedidos.model.VerifiedOwnerMapping;
import com.pedidos360.pedidos.repository.VerifiedOwnerMappingRepository;
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
    private final AuditEventRepository audit;
    private final CustomerDirectory directory;
    private final VerifiedOwnerMappingRepository mappings;
    public OrderApiController(PedidoRepository repository, OrderEventPublisher publisher, AuditEventRepository audit,
                              CustomerDirectory directory, VerifiedOwnerMappingRepository mappings) {
        this.repository = repository; this.publisher = publisher; this.audit = audit; this.directory = directory;
        this.mappings = mappings;
    }

    public record OrderInput(String customerName, String customerId, String customerTenantId,
                             String detail, Long total, List<PedidoItem> items) {}
    public record StatusInput(String status, Long expectedVersion) {}
    public record OwnerInput(String customerId, String customerTenantId) {}
    public record LegacyOwnerInput(Long orderId, String customerId, String customerTenantId) {}
    public record Page<T>(List<T> items, long total, int page, int pageSize) {}
    public record OrderView(String id, String customerName, String customerId, String customerTenantId,
                            String detail, Double total, String status, String createdAt,
                            String updatedAt, Long version, List<PedidoStatusEvent> history, List<PedidoItem> items) {}

    @GetMapping
    @Transactional
    public Page<OrderView> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status, @RequestParam(required = false) String q,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 100 || (from != null && to != null && from.isAfter(to))) bad();
        String wanted = status == null || status.isBlank() ? null : normalized(status);
        String search = q == null ? "" : q.strip().toLowerCase();
        boolean admin = jwt.getClaimAsStringList("roles") != null && jwt.getClaimAsStringList("roles").contains("Admin");
        String oid = jwt.getClaimAsString("oid"), tid = jwt.getClaimAsString("tid");
        if (!admin && (blank(oid) || blank(tid))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Specification<Pedido> criteria = (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (!admin) {
                conditions.add(cb.equal(root.get("customerId"), oid));
                conditions.add(cb.equal(root.get("customerTenantId"), tid));
            }
            if (wanted != null) {
                List<String> equivalents = switch (wanted) {
                    case "CREATED" -> List.of("CREATED", "PENDIENTE");
                    case "IN_PROGRESS" -> List.of("IN_PROGRESS", "EN_PREPARACION");
                    case "COMPLETED" -> List.of("COMPLETED", "ENTREGADO");
                    default -> List.of(wanted);
                };
                conditions.add(root.get("estado").in(equivalents));
            }
            if (!search.isEmpty()) {
                String pattern = "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                conditions.add(cb.or(cb.like(cb.lower(root.get("cliente")), pattern, '\\'),
                    cb.like(cb.lower(root.get("detalle")), pattern, '\\'),
                    cb.like(root.get("id").as(String.class), pattern, '\\')));
            }
            if (from != null) conditions.add(cb.greaterThanOrEqualTo(root.get("fecha"), from.atStartOfDay()));
            if (to != null) conditions.add(cb.lessThan(root.get("fecha"), to.plusDays(1).atStartOfDay()));
            return cb.and(conditions.toArray(Predicate[]::new));
        };
        var result = repository.findAll(criteria, PageRequest.of(page, pageSize, Sort.by(Sort.Order.desc("fecha"), Sort.Order.desc("id"))));
        return new Page<>(result.getContent().stream().map(this::view).toList(), result.getTotalElements(), page, pageSize);
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
        var owner = directory.resolve(input.customerId(), input.customerTenantId());
        Pedido p = new Pedido();
        apply(p, input);
        p.setCustomerId(owner.id()); p.setCustomerTenantId(owner.tenantId());
        p.setCliente(owner.name());
        p.setEstado(OrderStatus.CREATED.name());
        p.setFecha(java.time.LocalDateTime.now(SANTIAGO));
        p.getHistory().add(event(OrderStatus.CREATED.name(), jwt));
        repository.saveAndFlush(p);
        audit.save(new AuditEvent("order-created-" + p.getId(), "ORDER_CREATED", "Pedido " + p.getId() + " creado", actorEmail(jwt), OffsetDateTime.now()));
        publisher.publicarPedidoCreado(p);
        return view(p);
    }

    @PutMapping("/{id}")
    @Transactional
    public OrderView update(@PathVariable Long id, @RequestBody OrderInput input) {
        validate(input);
        Pedido p = find(id);
        if (!java.util.Objects.equals(p.getCustomerId(), input.customerId()) ||
            !java.util.Objects.equals(p.getCustomerTenantId(), input.customerTenantId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Usa la reasignación administrativa para cambiar propietario");
        apply(p, input);
        p.setUpdatedAt(OffsetDateTime.now());
        repository.saveAndFlush(p);
        return view(p);
    }

    @PatchMapping("/{id}/owner")
    @Transactional
    public OrderView reassign(@PathVariable Long id, @RequestBody OwnerInput input, @AuthenticationPrincipal Jwt jwt) {
        if (input == null) bad();
        var owner = directory.resolve(input.customerId(), input.customerTenantId());
        Pedido p = find(id);
        p.setCustomerId(owner.id()); p.setCustomerTenantId(owner.tenantId()); p.setCliente(owner.name());
        p.setUpdatedAt(OffsetDateTime.now());
        repository.saveAndFlush(p);
        audit.save(new AuditEvent("order-owner-" + p.getId() + "-" + p.getVersion(), "ORDER_OWNER_CHANGED",
            "Propietario del pedido " + p.getId() + " reasignado", actorEmail(jwt), OffsetDateTime.now()));
        return view(p);
    }

    @PostMapping("/ownership-migration")
    @Transactional
    public Map<String, Integer> migrateOwners(@RequestBody List<LegacyOwnerInput> input, @AuthenticationPrincipal Jwt jwt) {
        if (input == null || input.isEmpty() || input.size() > 100) bad();
        if (input.stream().anyMatch(x -> x == null || x.orderId() == null) ||
            input.stream().map(LegacyOwnerInput::orderId).distinct().count() != input.size()) bad();
        for (LegacyOwnerInput item : input) {
            Pedido p = find(item.orderId());
            if (p.getCustomerId() != null || p.getCustomerTenantId() != null || mappings.existsById(item.orderId()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Pedido ya asignado: " + item.orderId());
            var owner = directory.resolve(item.customerId(), item.customerTenantId());
            p.setCustomerId(owner.id()); p.setCustomerTenantId(owner.tenantId()); p.setCliente(owner.name());
            p.setUpdatedAt(OffsetDateTime.now());
            repository.saveAndFlush(p);
            mappings.save(new VerifiedOwnerMapping(p.getId(), owner.id(), owner.tenantId(), actorEmail(jwt), OffsetDateTime.now()));
            audit.save(new AuditEvent("order-owner-migrated-" + p.getId(), "ORDER_OWNER_CHANGED",
                "Propietario histórico del pedido " + p.getId() + " verificado", actorEmail(jwt), OffsetDateTime.now()));
        }
        return Map.of("migrated", input.size());
    }

    @PatchMapping("/{id}/status")
    @Transactional
    public OrderView status(@PathVariable Long id, @RequestBody StatusInput input, @AuthenticationPrincipal Jwt jwt) {
        if (input == null || input.expectedVersion() == null || input.expectedVersion() < 0) bad();
        OrderStatus next = OrderStatus.fromInput(input.status())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado desconocido"));
        Pedido p = find(id);
        if (!input.expectedVersion().equals(p.getVersion()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El pedido cambió; vuelve a cargarlo");
        OrderStatus current = OrderStatus.fromStored(p.getEstado())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Estado histórico sin equivalencia"));
        if (!current.canTransitionTo(next))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transición de estado inválida");
        p.setEstado(next.name());
        p.setUpdatedAt(OffsetDateTime.now());
        p.getHistory().add(event(next.name(), jwt));
        repository.saveAndFlush(p);
        audit.save(new AuditEvent("order-status-" + p.getId() + "-" + p.getVersion(),
            next == OrderStatus.CANCELLED ? "ORDER_CANCELLED" : "ORDER_STATUS_CHANGED",
            "Pedido " + p.getId() + " pasó a " + next, actorEmail(jwt), OffsetDateTime.now()));
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
        p.setDetalle(input.detail().strip());
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
        return OrderStatus.fromStored(s).map(Enum::name)
            .orElse(s == null || s.isBlank() ? "UNKNOWN" : s.strip().toUpperCase(java.util.Locale.ROOT));
    }
    private PedidoStatusEvent event(String status, Jwt jwt) {
        return new PedidoStatusEvent(status, OffsetDateTime.now(), jwt.getClaimAsString("name") == null ? jwt.getSubject() : jwt.getClaimAsString("name"), "Admin");
    }
    private String actorEmail(Jwt jwt) {
        String email = jwt.getClaimAsString("preferred_username");
        if (email == null) email = jwt.getClaimAsString("email");
        return email;
    }
    private OrderView view(Pedido p) {
        String date = p.getFecha() == null ? "" : p.getFecha().atZone(SANTIAGO).toOffsetDateTime().toString();
        return new OrderView(String.valueOf(p.getId()), p.getCliente(), p.getCustomerId(), p.getCustomerTenantId(),
            p.getDetalle(), p.getTotal(), normalized(p.getEstado()), date,
            p.getUpdatedAt() == null ? null : p.getUpdatedAt().toString(), p.getVersion(),
            List.copyOf(p.getHistory().reversed()), List.copyOf(p.getItems()));
    }
}
