package com.pedidos360.pedidos.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.pedidos.repository.PedidoRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Estado", description = "Vista agregada protegida para Admin")
public class HealthController {
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
    private final PedidoRepository orders;
    private final ObjectMapper mapper;
    private final String bffUrl;
    private final String productsUrl;
    private final String auditUrl;
    private final String reportUrl;
    private final String internalKey;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public HealthController(PedidoRepository orders, ObjectMapper mapper,
                            @Value("${app.health.bff-url:}") String bffUrl,
                            @Value("${app.health.products-url:}") String productsUrl,
                            @Value("${app.health.audit-url:}") String auditUrl,
                            @Value("${app.health.report-url:}") String reportUrl,
                            @Value("${app.internal.key:}") String internalKey) {
        this.orders = orders; this.mapper = mapper; this.bffUrl = bffUrl;
        this.productsUrl = productsUrl; this.auditUrl = auditUrl;
        this.reportUrl = reportUrl; this.internalKey = internalKey;
    }
    public record Entry(String name, String status) {}
    public record Result(List<Entry> services) {}
    @GetMapping("/api/health")
    @Operation(summary = "Consulta el estado de servicios conocidos",
        description = "Solo Admin. Omite servicios sin URL o clave interna; la UI los muestra SIN DATOS")
    public Result check() {
        List<CompletableFuture<Entry>> remote = new ArrayList<>();
        addProbe(remote, "bff", bffUrl);
        addProbe(remote, "products", productsUrl);
        addProbe(remote, "report", reportUrl);
        addProbe(remote, "audit", auditUrl);
        List<Entry> services = new ArrayList<>();
        services.add(new Entry("orders", ordersUp() ? "UP" : "DOWN"));
        for (CompletableFuture<Entry> probe : remote) services.add(probe.join());
        return new Result(List.copyOf(services));
    }

    private boolean ordersUp() {
        try { orders.count(); return true; } catch (Exception e) { return false; }
    }

    private void addProbe(List<CompletableFuture<Entry>> probes, String name, String configuredUrl) {
        if (configuredUrl == null || configuredUrl.isBlank() || internalKey.isBlank()) return;
        try {
            URI uri = URI.create(configuredUrl.strip());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || !"/internal/health".equals(uri.getPath()) || uri.getHost() == null) {
                probes.add(CompletableFuture.completedFuture(new Entry(name, "DOWN")));
                return;
            }
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(PROBE_TIMEOUT)
                .header("X-Internal-Key", internalKey).GET().build();
            probes.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> new Entry(name,
                    error == null && verifiedUp(response, name) ? "UP" : "DOWN")));
        } catch (Exception e) {
            probes.add(CompletableFuture.completedFuture(new Entry(name, "DOWN")));
        }
    }

    private boolean verifiedUp(HttpResponse<String> response, String expectedName) {
        if (response.statusCode() != 200) return false;
        try {
            var body = mapper.readTree(response.body());
            return expectedName.equals(body.path("service").asText())
                && "UP".equals(body.path("status").asText());
        } catch (Exception e) { return false; }
    }
}
