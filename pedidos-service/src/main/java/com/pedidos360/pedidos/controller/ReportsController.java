package com.pedidos360.pedidos.controller;

import com.pedidos360.pedidos.model.Pedido;
import com.pedidos360.pedidos.model.PedidoItem;
import com.pedidos360.pedidos.model.PedidoStatusEvent;
import com.pedidos360.pedidos.model.OrderStatus;
import com.pedidos360.pedidos.repository.PedidoRepository;
import jakarta.transaction.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/reports")
public class ReportsController {
    private static final ZoneId ZONE = ZoneId.of("America/Santiago");
    private final PedidoRepository repository;
    public ReportsController(PedidoRepository repository) { this.repository = repository; }
    public record Kpis(long totalOrders, double monthlySales, Double activeUsers, Double averageLeadTimeDays,
                       long pendingOrders, long inProgressOrders, long completedToday, double totalSales,
                       Double averageOrderValue, Double cancellationRate, long completedOrders,
                       Map<String, Long> byStatus, String updatedAt) {}
    public record Sales(String month, double sales, long orderCount) {}
    public record LeadTime(String month, double days) {}
    public record TopProduct(String productId, String name, long units) {}

    @GetMapping("/kpis")
    @Transactional
    public Kpis kpis(@RequestParam(defaultValue = "30") int days) {
        List<Pedido> orders = range(days);
        LocalDate today = LocalDate.now(ZONE);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (String s : List.of("CREATED", "ACCEPTED", "IN_PROGRESS", "COMPLETED", "CANCELLED"))
            byStatus.put(s, orders.stream().filter(p -> status(p).equals(s)).count());
        List<Pedido> completed = orders.stream().filter(p -> status(p).equals("COMPLETED")).toList();
        double sales = completed.stream().mapToDouble(p -> p.getTotalClp() == null ? 0 : p.getTotalClp()).sum();
        List<Double> leads = completed.stream().map(this::leadDays).filter(Objects::nonNull).toList();
        Double leadAverage = leads.isEmpty() ? null : leads.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double monthly = completed.stream().filter(p -> p.getFecha() != null && YearMonth.from(p.getFecha()).equals(YearMonth.from(today)))
            .mapToDouble(p -> p.getTotalClp() == null ? 0 : p.getTotalClp()).sum();
        long todayDone = completed.stream().filter(p -> p.getHistory().stream().anyMatch(e -> "COMPLETED".equals(e.getStatus())
            && e.getChangedAt() != null && e.getChangedAt().atZoneSameInstant(ZONE).toLocalDate().equals(today))).count();
        return new Kpis(orders.size(), monthly, null, leadAverage, byStatus.get("CREATED"),
            byStatus.get("ACCEPTED") + byStatus.get("IN_PROGRESS"), todayDone, sales,
            completed.isEmpty() ? null : sales / completed.size(), orders.isEmpty() ? null : (double) byStatus.get("CANCELLED") / orders.size(),
            completed.size(), byStatus, OffsetDateTime.now().toString());
    }

    @GetMapping("/sales")
    @Transactional
    public List<Sales> sales(@RequestParam(defaultValue = "365") int days) {
        return range(days).stream().filter(p -> status(p).equals("COMPLETED") && p.getFecha() != null)
            .collect(Collectors.groupingBy(p -> YearMonth.from(p.getFecha()), TreeMap::new, Collectors.toList()))
            .entrySet().stream().map(e -> new Sales(e.getKey().toString(),
                e.getValue().stream().mapToDouble(p -> p.getTotalClp() == null ? 0 : p.getTotalClp()).sum(), e.getValue().size())).toList();
    }

    @GetMapping("/lead-time")
    @Transactional
    public List<LeadTime> leadTime(@RequestParam(defaultValue = "30") int days) {
        return range(days).stream().filter(p -> status(p).equals("COMPLETED") && p.getFecha() != null && leadDays(p) != null)
            .collect(Collectors.groupingBy(p -> YearMonth.from(p.getFecha()), TreeMap::new, Collectors.toList()))
            .entrySet().stream().map(e -> new LeadTime(e.getKey().toString(),
                e.getValue().stream().mapToDouble(this::leadDays).average().orElseThrow())).toList();
    }

    @GetMapping("/top-products")
    @Transactional
    public List<TopProduct> topProducts(@RequestParam(defaultValue = "30") int days) {
        Map<String, TopProduct> totals = new HashMap<>();
        range(days).stream().filter(p -> status(p).equals("COMPLETED")).forEach(p -> {
            for (PedidoItem item : p.getItems()) totals.merge(item.getProductId(),
                new TopProduct(item.getProductId(), item.getName(), item.getQuantity()),
                (a, b) -> new TopProduct(a.productId(), a.name(), a.units() + b.units()));
        });
        return totals.values().stream().sorted(Comparator.comparingLong(TopProduct::units).reversed().thenComparing(TopProduct::productId)).toList();
    }

    private List<Pedido> range(int days) {
        if (days < 1 || days > 3650) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days fuera de rango");
        LocalDate start = LocalDate.now(ZONE).minusDays(days - 1L);
        return repository.findAll().stream().filter(p -> p.getFecha() != null
            && (p.getInventoryState() == null || "READY".equals(p.getInventoryState()))
            && !p.getFecha().toLocalDate().isBefore(start)).toList();
    }
    private String status(Pedido p) {
        return OrderStatus.fromStored(p.getEstado()).map(Enum::name).orElse("UNKNOWN");
    }
    private Double leadDays(Pedido p) {
        Optional<PedidoStatusEvent> done = p.getHistory().stream().filter(e -> "COMPLETED".equals(e.getStatus()) && e.getChangedAt() != null).findFirst();
        if (done.isEmpty() || p.getFecha() == null) return null;
        Instant created = p.getFecha().atZone(ZONE).toInstant();
        return Math.max(0, Duration.between(created, done.get().getChangedAt().toInstant()).toMinutes() / 1440.0);
    }
}
