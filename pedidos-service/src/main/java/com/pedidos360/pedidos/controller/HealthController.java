package com.pedidos360.pedidos.controller;

import com.pedidos360.pedidos.repository.AuditEventRepository;
import com.pedidos360.pedidos.repository.PedidoRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final PedidoRepository orders;
    private final AuditEventRepository audit;
    private final RabbitTemplate rabbit;
    private final URI productsUrl;
    private final String auditUrl;
    private final String auditInternalKey;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public HealthController(PedidoRepository orders, AuditEventRepository audit, RabbitTemplate rabbit,
                            @Value("${app.health.products-url:http://localhost:8082/api/products}") URI productsUrl,
                            @Value("${app.health.audit-url:}") String auditUrl,
                            @Value("${app.internal.key:}") String auditInternalKey) {
        this.orders = orders; this.audit = audit; this.rabbit = rabbit; this.productsUrl = productsUrl;
        this.auditUrl = auditUrl; this.auditInternalKey = auditInternalKey;
    }
    public record Entry(String name, String status) {}
    public record Result(List<Entry> services) {}
    @GetMapping("/api/health")
    public Result check() {
        String local = databaseUp() ? "UP" : "DOWN";
        return new Result(List.of(new Entry("bff", "DOWN"), new Entry("orders", local),
            new Entry("products", productsUp() ? "UP" : "DOWN"),
            new Entry("report", local), new Entry("audit", auditUp() ? "UP" : "DOWN")));
    }
    private boolean databaseUp() {
        try { orders.count(); return true; } catch (Exception e) { return false; }
    }
    private boolean productsUp() {
        try {
            HttpRequest request = HttpRequest.newBuilder(productsUrl).timeout(Duration.ofSeconds(3)).GET().build();
            // 401 confirma que el servicio protegido está respondiendo sin credenciales.
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 401;
        } catch (Exception e) { return false; }
    }
    private boolean brokerUp() {
        try { return Boolean.TRUE.equals(rabbit.execute(channel -> channel.isOpen())); }
        catch (Exception e) { return false; }
    }
    private boolean auditUp() {
        if (auditUrl.isBlank()) {
            try { audit.count(); return databaseUp() && brokerUp(); }
            catch (Exception e) { return false; }
        }
        if (auditInternalKey.isBlank()) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(auditUrl))
                .timeout(Duration.ofSeconds(5)).header("X-Internal-Key", auditInternalKey).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) { return false; }
    }
}
