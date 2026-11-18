package com.pedidos360.productos.controller;

import com.pedidos360.productos.model.Producto;
import com.pedidos360.productos.repository.ProductoRepository;
import com.pedidos360.productos.messaging.ProductAuditPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.transaction.Transactional;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/products")
public class ProductApiController {
    private final ProductoRepository repository;
    private final ProductAuditPublisher audit;
    public ProductApiController(ProductoRepository repository, ProductAuditPublisher audit) {
        this.repository = repository; this.audit = audit;
    }
    public record ProductInput(String name, String category, Double price, Integer stock) {}
    public record StockInput(Integer delta) {}
    public record ProductView(String id, String name, String category, Double price, Integer stock) {}

    @GetMapping
    public List<ProductView> list() { return repository.findAll().stream().map(this::view).toList(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ProductView create(@RequestBody ProductInput input) {
        validate(input);
        Producto p = new Producto();
        apply(p, input);
        return view(repository.saveAndFlush(p));
    }

    @PutMapping("/{id}")
    @Transactional
    public ProductView update(@PathVariable Long id, @RequestBody ProductInput input, @AuthenticationPrincipal Jwt jwt) {
        validate(input);
        Producto p = find(id);
        apply(p, input);
        repository.saveAndFlush(p);
        audit.updated(p, email(jwt));
        return view(p);
    }

    @PatchMapping("/{id}/stock")
    @Transactional
    public ProductView stock(@PathVariable Long id, @RequestBody StockInput input, @AuthenticationPrincipal Jwt jwt) {
        if (input == null || input.delta() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Producto p = find(id);
        if (p.getStock() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock no inicializado");
        long next = (long) p.getStock() + input.delta();
        if (next < 0 || next > Integer.MAX_VALUE) throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock fuera de rango");
        p.setStock((int) next);
        repository.saveAndFlush(p);
        audit.updated(p, email(jwt));
        return view(p);
    }
    private Producto find(Long id) { return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)); }
    private ProductView view(Producto p) { return new ProductView(String.valueOf(p.getId()), p.getNombre(), p.getCategoria(), p.getPrecio(), p.getStock()); }
    private void apply(Producto p, ProductInput i) {
        p.setNombre(i.name().strip()); p.setCategoria(i.category().strip()); p.setPrecio(i.price()); p.setStock(i.stock());
    }
    private void validate(ProductInput i) {
        if (i == null || i.name() == null || i.name().isBlank() || i.category() == null || i.category().isBlank()
            || i.price() == null || !Double.isFinite(i.price()) || i.price() < 0 || i.stock() == null || i.stock() < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
    private String email(Jwt jwt) {
        String value = jwt.getClaimAsString("preferred_username");
        return value == null ? jwt.getClaimAsString("email") : value;
    }
}
