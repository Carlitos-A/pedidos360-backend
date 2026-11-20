package com.pedidos360.audit.controller;

import com.pedidos360.audit.model.AuditEvent;
import com.pedidos360.audit.repository.AuditEventRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/audit/events")
public class AuditController {
    private static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");
    private static final Set<String> TYPES = Set.of("ORDER_CREATED", "ORDER_STATUS_CHANGED",
        "ORDER_CANCELLED", "ORDER_OWNER_CHANGED", "PRODUCT_UPDATED", "USER_LOGIN");
    private final AuditEventRepository repository;
    public AuditController(AuditEventRepository repository) { this.repository = repository; }
    public record EventView(String id, String type, String description, String actorEmail, String occurredAt) {}
    public record Page(List<EventView> items, long total, int page, int pageSize) {}

    @GetMapping
    public Page list(@RequestParam(required = false) String user, @RequestParam(required = false) String type,
                     @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 100 || from != null && to != null && from.isAfter(to))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtros inválidos");
        String email = user == null ? "" : user.strip().toLowerCase(Locale.ROOT);
        String kind = type == null ? "" : type.strip();
        if (!kind.isEmpty() && !TYPES.contains(kind))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo inválido");
        Specification<AuditEvent> filters = (root, query, cb) -> {
            List<Predicate> clauses = new ArrayList<>();
            if (!email.isEmpty()) {
                String pattern = "%" + email.replace("\\", "\\\\").replace("%", "\\%")
                    .replace("_", "\\_") + "%";
                clauses.add(cb.like(cb.lower(root.get("actorEmail")), pattern, '\\'));
            }
            if (!kind.isEmpty()) clauses.add(cb.equal(root.get("type"), kind));
            if (from != null) clauses.add(cb.greaterThanOrEqualTo(root.get("occurredAt"),
                from.atStartOfDay(SANTIAGO).toOffsetDateTime()));
            if (to != null) clauses.add(cb.lessThan(root.get("occurredAt"),
                to.plusDays(1).atStartOfDay(SANTIAGO).toOffsetDateTime()));
            return cb.and(clauses.toArray(Predicate[]::new));
        };
        var result = repository.findAll(filters, PageRequest.of(page, pageSize,
            Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))));
        return new Page(result.getContent().stream().map(e -> new EventView(String.valueOf(e.getId()),
            e.getType(), e.getDescription(), e.getActorEmail() == null ? "" : e.getActorEmail(),
            e.getOccurredAt().toString())).toList(), result.getTotalElements(), page, pageSize);
    }
}
