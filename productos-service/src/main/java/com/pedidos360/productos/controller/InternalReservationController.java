package com.pedidos360.productos.controller;

import com.pedidos360.productos.model.ProductReservation;
import com.pedidos360.productos.model.Producto;
import com.pedidos360.productos.repository.ProductReservationRepository;
import com.pedidos360.productos.repository.ProductoRepository;
import com.pedidos360.productos.messaging.ProductAuditPublisher;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/reservations")
public class InternalReservationController {
    private final ProductoRepository products;
    private final ProductReservationRepository reservations;
    private final String internalKey;
    private final ProductAuditPublisher audit;

    public InternalReservationController(ProductoRepository products, ProductReservationRepository reservations,
                                         ProductAuditPublisher audit, @Value("${app.internal.key:}") String internalKey) {
        this.products = products; this.reservations = reservations; this.audit = audit; this.internalKey = internalKey;
    }

    public record Line(Long productId, Integer quantity, Long expectedUnitPrice) {}
    public record ReserveInput(String customerId, String customerTenantId, boolean validatePrices, List<Line> items) {}
    public record QuotedLine(String productId, String name, Integer quantity, Long unitPrice) {}
    public record Quote(List<QuotedLine> items, Long total) {}

    @PutMapping("/{key}")
    @Transactional
    public Quote reserve(@PathVariable String key, @RequestHeader(value = "X-Internal-Key", required = false) String provided,
                         @RequestBody ReserveInput input) {
        if (internalKey.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Clave interna no configurada");
        if (provided == null || !MessageDigest.isEqual(internalKey.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (key.isBlank() || key.length() > 100 || input == null || blank(input.customerId()) || blank(input.customerTenantId())
            || input.items() == null || input.items().size() > 100
            || input.items().stream().anyMatch(x -> x == null || x.productId() == null || x.productId() < 1
                || x.quantity() == null || x.quantity() < 1 || x.expectedUnitPrice() == null || x.expectedUnitPrice() < 0)
            || input.items().stream().map(Line::productId).distinct().count() != input.items().size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);

        ProductReservation reservation = reservations.lockById(key).orElse(null);
        if (reservation != null && (!Objects.equals(reservation.getCustomerId(), input.customerId())
            || !Objects.equals(reservation.getCustomerTenantId(), input.customerTenantId())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reserva de otro propietario");
        Map<Long, Integer> old = reservation == null ? Map.of() : new HashMap<>(reservation.getQuantities());
        Map<Long, Line> desired = new HashMap<>();
        for (Line line : input.items()) desired.put(line.productId(), line);
        if (reservation != null && old.size() == desired.size()
            && desired.entrySet().stream().allMatch(e -> Objects.equals(old.get(e.getKey()), e.getValue().quantity()))) {
            try {
                List<QuotedLine> same = new ArrayList<>();
                long sum = 0;
                for (Line line : input.items()) {
                    Long price = reservation.getPrices().get(line.productId());
                    String name = reservation.getNames().get(line.productId());
                    if (price == null || name == null) throw new IllegalStateException("Reserva incompleta");
                    sum = Math.addExact(sum, Math.multiplyExact(price, line.quantity().longValue()));
                    if (sum > 9_007_199_254_740_991L) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Total fuera de rango");
                    same.add(new QuotedLine(String.valueOf(line.productId()), name, line.quantity(), price));
                }
                return new Quote(List.copyOf(same), sum);
            } catch (ArithmeticException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        }
        Map<Long, Producto> locked = new HashMap<>();
        TreeSet<Long> ids = new TreeSet<>(old.keySet());
        ids.addAll(desired.keySet());
        for (Long id : ids) {
            Producto p = products.lockById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Producto inexistente"));
            locked.put(id, p);
        }
        List<QuotedLine> quoted = new ArrayList<>();
        long total = 0;
        for (Line line : input.items()) {
            Producto p = locked.get(line.productId());
            Long price = p.getPriceClp();
            if (price == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Precio CLP sin equivalencia");
            if (input.validatePrices() && !price.equals(line.expectedUnitPrice()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "El precio cambió; vuelve a cargar el catálogo");
            try { total = Math.addExact(total, Math.multiplyExact(price, line.quantity().longValue())); }
            catch (ArithmeticException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Total fuera de rango"); }
            if (total > 9_007_199_254_740_991L) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Total fuera de rango");
            quoted.add(new QuotedLine(String.valueOf(p.getId()), p.getNombre(), line.quantity(), price));
        }
        for (Long id : ids) {
            Producto p = locked.get(id);
            if (p.getStock() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock no inicializado");
            long next = (long) p.getStock() + old.getOrDefault(id, 0) -
                (desired.containsKey(id) ? desired.get(id).quantity() : 0);
            if (next < 0 || next > Integer.MAX_VALUE)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock insuficiente");
            if (next != p.getStock()) {
                p.setStock((int) next);
                audit.updated(p, "pedidos-service");
            }
        }
        if (reservation == null) reservation = new ProductReservation(key, input.customerId(), input.customerTenantId());
        reservation.getQuantities().clear();
        reservation.getPrices().clear();
        reservation.getNames().clear();
        for (Line line : input.items()) reservation.getQuantities().put(line.productId(), line.quantity());
        for (QuotedLine line : quoted) {
            Long id = Long.valueOf(line.productId());
            reservation.getPrices().put(id, line.unitPrice());
            reservation.getNames().put(id, line.name());
        }
        reservations.saveAndFlush(reservation);
        products.flush();
        return new Quote(List.copyOf(quoted), total);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
