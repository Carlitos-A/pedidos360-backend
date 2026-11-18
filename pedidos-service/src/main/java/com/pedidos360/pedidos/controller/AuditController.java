package com.pedidos360.pedidos.controller;

import com.pedidos360.pedidos.model.AuditEvent;
import com.pedidos360.pedidos.repository.AuditEventRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/audit/events")
public class AuditController {
    private static final ZoneId ZONE = ZoneId.of("America/Santiago");
    private final AuditEventRepository repository;
    public AuditController(AuditEventRepository repository) { this.repository = repository; }
    public record EventView(String id, String type, String description, String actorEmail, String occurredAt) {}
    public record Page(List<EventView> items, long total, int page, int pageSize) {}
    @GetMapping
    public Page list(@RequestParam(required = false) String user, @RequestParam(required = false) String type,
                     @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 100 || from != null && to != null && from.isAfter(to))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String email = user == null ? "" : user.strip().toLowerCase();
        String kind = type == null ? "" : type.strip();
        if (!kind.isEmpty() && !List.of("ORDER_CREATED", "ORDER_STATUS_CHANGED", "ORDER_CANCELLED", "ORDER_OWNER_CHANGED", "PRODUCT_UPDATED", "USER_LOGIN").contains(kind))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo inválido");
        List<AuditEvent> matches = repository.findAll().stream()
            .filter(e -> email.isEmpty() || e.getActorEmail() != null && e.getActorEmail().toLowerCase().contains(email))
            .filter(e -> kind.isEmpty() || e.getType().equals(kind))
            .filter(e -> from == null || !e.getOccurredAt().atZoneSameInstant(ZONE).toLocalDate().isBefore(from))
            .filter(e -> to == null || !e.getOccurredAt().atZoneSameInstant(ZONE).toLocalDate().isAfter(to))
            .sorted(Comparator.comparing(AuditEvent::getOccurredAt).reversed().thenComparing(AuditEvent::getId, Comparator.reverseOrder())).toList();
        long start = (long) page * pageSize;
        List<EventView> items = start >= matches.size() ? List.of() : matches.subList((int) start, (int) Math.min(matches.size(), start + pageSize))
            .stream().map(e -> new EventView(String.valueOf(e.getId()), e.getType(), e.getDescription(),
                e.getActorEmail() == null ? "" : e.getActorEmail(), e.getOccurredAt().toString())).toList();
        return new Page(items, matches.size(), page, pageSize);
    }
}
