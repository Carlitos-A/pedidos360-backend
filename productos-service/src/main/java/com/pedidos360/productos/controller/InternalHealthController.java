package com.pedidos360.productos.controller;

import com.pedidos360.productos.repository.ProductoRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InternalHealthController {
    private final ProductoRepository products;
    private final String key;

    public InternalHealthController(ProductoRepository products, @Value("${app.internal.key:}") String key) {
        this.products = products; this.key = key;
    }

    @GetMapping("/internal/health")
    public Map<String, String> check(@RequestHeader(value = "X-Internal-Key", required = false) String supplied) {
        if (key.isBlank() || supplied == null || !MessageDigest.isEqual(
            key.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        try {
            products.count();
            return Map.of("service", "products", "status", "UP");
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
