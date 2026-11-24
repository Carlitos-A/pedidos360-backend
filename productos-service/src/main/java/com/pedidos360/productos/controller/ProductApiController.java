package com.pedidos360.productos.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;

import com.pedidos360.productos.model.Producto;
import com.pedidos360.productos.repository.ProductoRepository;
import com.pedidos360.productos.messaging.ProductAuditPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.transaction.Transactional;
import java.util.List;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/products")
@Tag(name = "Productos", description = "Catálogo compartido; escritura exclusiva de Admin")
public class ProductApiController {
    private final ProductoRepository repository;
    private final ProductAuditPublisher audit;
    public ProductApiController(ProductoRepository repository, ProductAuditPublisher audit) {
        this.repository = repository; this.audit = audit;
    }
    @Schema(description = "Producto del catálogo con precio CLP entero y stock no negativo")
    public record ProductInput(String name, String category, BigDecimal price, Integer stock) {}
    @Schema(description = "Ajuste atómico de stock; delta positivo o negativo")
    public record StockInput(Integer delta) {}
    public record ProductView(String id, String name, String category, Long price, Integer stock) {}

    @GetMapping
    @Operation(summary = "Lista el catálogo compartido", description = "Admin y Cliente pueden consultar los productos")
    public List<ProductView> list(@AuthenticationPrincipal Jwt jwt) {
        return repository.findAll().stream().map(this::view).toList();
    }

    @PostMapping
    @Operation(summary = "Crea un producto", description = "Solo Admin")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ProductView create(@RequestBody ProductInput input, @AuthenticationPrincipal Jwt jwt) {
        validate(input);
        Producto p = new Producto();
        apply(p, input);
        repository.saveAndFlush(p);
        audit.updated(p, jwt);
        return view(p);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edita un producto", description = "Solo Admin")
    @Transactional
    public ProductView update(@PathVariable Long id, @RequestBody ProductInput input, @AuthenticationPrincipal Jwt jwt) {
        validate(input);
        Producto p = find(id);
        apply(p, input);
        repository.saveAndFlush(p);
        audit.updated(p, jwt);
        return view(p);
    }

    @PatchMapping("/{id}/stock")
    @Operation(summary = "Ajusta stock", description = "Solo Admin; no admite stock negativo y puede responder 409")
    @Transactional
    public ProductView stock(@PathVariable Long id, @RequestBody StockInput input, @AuthenticationPrincipal Jwt jwt) {
        if (input == null || input.delta() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Producto p = find(id);
        if (p.getStock() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock no inicializado");
        long next = (long) p.getStock() + input.delta();
        if (next < 0 || next > Integer.MAX_VALUE) throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock fuera de rango");
        p.setStock((int) next);
        repository.saveAndFlush(p);
        audit.updated(p, jwt);
        return view(p);
    }
    private Producto find(Long id) { return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)); }
    private ProductView view(Producto p) { return new ProductView(String.valueOf(p.getId()), p.getNombre(), p.getCategoria(), p.getPriceClp(), p.getStock()); }
    private void apply(Producto p, ProductInput i) {
        p.setNombre(i.name().strip()); p.setCategoria(i.category().strip()); p.setPriceClp(i.price().longValueExact()); p.setStock(i.stock());
    }
    private void validate(ProductInput i) {
        if (i == null || i.name() == null || i.name().isBlank() || i.name().length() > 200
            || i.category() == null || i.category().isBlank() || i.category().length() > 200
            || i.price() == null || !integerClp(i.price()) || i.stock() == null || i.stock() < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
    private boolean integerClp(BigDecimal price) {
        try { return price.signum() >= 0 && price.longValueExact() <= 9_007_199_254_740_991L; }
        catch (ArithmeticException ex) { return false; }
    }
}
