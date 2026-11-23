package com.pedidos360.report.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.report.messaging.ReportOrderEvent;
import com.pedidos360.report.model.ReportOrder;
import com.pedidos360.report.repository.ReportOrderRepository;
import com.pedidos360.report.repository.ReportReceiptRepository;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/reports")
@Tag(name = "Reportes", description = "Analítica Kafka, solo Admin; days usa días inclusivos en America/Santiago")
public class ReportsController {
    private static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");
    private static final List<String> STATUSES = List.of("CREATED", "ACCEPTED", "IN_PROGRESS", "COMPLETED", "CANCELLED");
    private final ReportOrderRepository orders;
    private final ReportReceiptRepository receipts;
    private final ObjectMapper mapper;
    public ReportsController(ReportOrderRepository orders, ReportReceiptRepository receipts, ObjectMapper mapper) {
        this.orders = orders; this.receipts = receipts; this.mapper = mapper;
    }
    @Schema(description = "Indicadores calculados desde la proyección Kafka; campos sin base verificable son null")
    public record Kpis(long totalOrders, long monthlySales, Double activeUsers, Double averageLeadTimeDays,
                       long pendingOrders, long inProgressOrders, long completedToday, long totalSales,
                       Double averageOrderValue, Double cancellationRate, long completedOrders,
                       Map<String, Long> byStatus, String updatedAt) {}
    public record Sales(String month, long sales, long orderCount) {}
    public record LeadTime(String month, double days) {}
    public record TopProduct(String productId, String name, long units) {}

    @GetMapping("/kpis")
    @Operation(summary = "Indicadores de pedidos y ventas")
    public Kpis kpis(@RequestParam(defaultValue = "30") int days) {
        Instant start = start(days);
        LocalDate today = LocalDate.now(SANTIAGO);
        List<ReportOrder> created = orders.findByCreatedAtGreaterThanEqual(start);
        List<ReportOrder> completed = orders.findByStatusAndCompletedAtGreaterThanEqual("COMPLETED", start);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (String status : STATUSES) byStatus.put(status, created.stream().filter(o -> status.equals(o.getStatus())).count());
        long sales = completed.stream().mapToLong(ReportOrder::getTotal).sum();
        double lead = completed.stream().mapToDouble(this::leadDays).average().orElse(Double.NaN);
        long monthly = completed.stream().filter(o -> month(o.getCompletedAt()).equals(YearMonth.from(today)))
            .mapToLong(ReportOrder::getTotal).sum();
        long todayDone = completed.stream().filter(o -> o.getCompletedAt().atZone(SANTIAGO).toLocalDate().equals(today)).count();
        return new Kpis(created.size(), monthly, null, Double.isNaN(lead) ? null : lead,
            byStatus.get("CREATED"), byStatus.get("ACCEPTED") + byStatus.get("IN_PROGRESS"),
            todayDone, sales, completed.isEmpty() ? null : (double) sales / completed.size(),
            created.isEmpty() ? null : (double) byStatus.get("CANCELLED") / created.size(),
            completed.size(), byStatus,
            receipts.findTopByOrderByProcessedAtDesc().map(r -> r.getProcessedAt().toString()).orElse(null));
    }

    @GetMapping("/sales")
    @Operation(summary = "Ventas y pedidos por mes")
    public List<Sales> sales(@RequestParam(defaultValue = "365") int days) {
        Instant start = start(days);
        return orders.findByStatusAndCompletedAtGreaterThanEqual("COMPLETED", start).stream()
            .collect(Collectors.groupingBy(o -> month(o.getCompletedAt()), TreeMap::new, Collectors.toList()))
            .entrySet().stream().map(e -> new Sales(e.getKey().toString(),
                e.getValue().stream().mapToLong(ReportOrder::getTotal).sum(), e.getValue().size())).toList();
    }

    @GetMapping("/lead-time")
    @Operation(summary = "Tiempo promedio de creación a completado por mes")
    public List<LeadTime> leadTime(@RequestParam(defaultValue = "30") int days) {
        Instant start = start(days);
        return orders.findByStatusAndCompletedAtGreaterThanEqual("COMPLETED", start).stream()
            .collect(Collectors.groupingBy(o -> month(o.getCompletedAt()), TreeMap::new, Collectors.toList()))
            .entrySet().stream().map(e -> new LeadTime(e.getKey().toString(),
                e.getValue().stream().mapToDouble(this::leadDays).average().orElseThrow())).toList();
    }

    @GetMapping("/top-products")
    @Operation(summary = "Productos con más unidades vendidas")
    public List<TopProduct> topProducts(@RequestParam(defaultValue = "30") int days) {
        Instant start = start(days);
        Map<String, TopProduct> totals = new HashMap<>();
        orders.findByStatusAndCompletedAtGreaterThanEqual("COMPLETED", start).forEach(o -> {
            for (ReportOrderEvent.Line item : items(o)) totals.merge(item.productId(),
                new TopProduct(item.productId(), item.name(), item.quantity()),
                (a, b) -> new TopProduct(a.productId(), a.name(), a.units() + b.units()));
        });
        return totals.values().stream().sorted(Comparator.comparingLong(TopProduct::units).reversed()
            .thenComparing(TopProduct::productId)).toList();
    }

    private List<ReportOrderEvent.Line> items(ReportOrder order) {
        try { return mapper.readValue(order.getItemsJson(), new TypeReference<>() {}); }
        catch (Exception ex) { throw new IllegalStateException("Snapshot de ítems dañado para pedido " + order.getId(), ex); }
    }
    private Instant start(int days) {
        if (days < 1 || days > 3650) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days fuera de rango");
        return LocalDate.now(SANTIAGO).minusDays(days - 1L).atStartOfDay(SANTIAGO).toInstant();
    }
    private YearMonth month(Instant instant) { return YearMonth.from(instant.atZone(SANTIAGO)); }
    private double leadDays(ReportOrder order) {
        return Math.max(0, Duration.between(order.getCreatedAt(), order.getCompletedAt()).toMinutes() / 1440.0);
    }
}
