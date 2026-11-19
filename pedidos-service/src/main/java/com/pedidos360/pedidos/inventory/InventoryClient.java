package com.pedidos360.pedidos.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.pedidos.model.PedidoItem;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InventoryClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String key;

    public InventoryClient(ObjectMapper mapper, @Value("${app.products.internal-url:http://localhost:8082}") String baseUrl,
                           @Value("${app.internal.key:}") String key) {
        this.mapper = mapper; this.baseUrl = baseUrl.replaceAll("/$", ""); this.key = key;
    }

    public record Line(Long productId, Integer quantity, Long expectedUnitPrice) {}
    public record Request(String customerId, String customerTenantId, boolean validatePrices, List<Line> items) {}
    public record QuotedLine(String productId, String name, Integer quantity, Long unitPrice) {}
    public record Quote(List<QuotedLine> items, Long total) {}

    public Quote reserve(String reservationKey, String customerId, String tenantId, List<PedidoItem> items, boolean validatePrices) {
        if (key.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Clave interna no configurada");
        List<Line> lines;
        try {
            lines = items.stream().map(item -> new Line(Long.valueOf(item.getProductId()),
                item.getQuantity(), item.getUnitPrice())).toList();
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID de producto inválido");
        }
        try {
            String body = mapper.writeValueAsString(new Request(customerId, tenantId, validatePrices, lines));
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/reservations/" + reservationKey))
                .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                .header("X-Internal-Key", key).PUT(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409) throw new ResponseStatusException(HttpStatus.CONFLICT, "Precio, propietario o stock cambió");
            if (response.statusCode() == 400) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ítems inválidos");
            if (response.statusCode() != 200) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Catálogo no disponible");
            Quote quote = mapper.readValue(response.body(), Quote.class);
            if (quote.items() == null || quote.total() == null || quote.total() < 0) throw new IllegalStateException("Cotización inválida");
            return quote;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Catálogo no disponible", ex);
        }
    }
}
