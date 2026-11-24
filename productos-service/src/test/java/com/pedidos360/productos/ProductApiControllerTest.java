package com.pedidos360.productos;

import com.pedidos360.productos.controller.ProductApiController;
import com.pedidos360.productos.model.Producto;
import com.pedidos360.productos.repository.ProductoRepository;
import com.pedidos360.productos.messaging.ProductAuditPublisher;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductApiControllerTest {
    @Test void adminCreaProductoSinIdentidadDeCliente() {
        ProductoRepository repository = mock(ProductoRepository.class);
        ProductAuditPublisher audit = mock(ProductAuditPublisher.class);
        when(repository.saveAndFlush(any(Producto.class))).thenAnswer(call -> {
            Producto product = call.getArgument(0);
            product.setId(8L);
            return product;
        });
        Jwt token = Jwt.withTokenValue("token").header("alg", "none")
            .claim("roles", List.of("Admin")).build();
        var product = new ProductApiController(repository, audit).create(
            new ProductApiController.ProductInput("Teclado", "Periféricos", BigDecimal.valueOf(15000), 4), token);
        assertEquals("8", product.id());
        assertEquals(15000L, product.price());
        assertEquals(4, product.stock());
        verify(audit).updated(any(Producto.class), same(token));
    }

    @Test void clienteConsultaCatalogoCompartidoInclusoProductosAnteriores() {
        ProductoRepository repository = mock(ProductoRepository.class);
        Producto old = new Producto("Producto anterior", "A", 100.0);
        old.setId(1L);
        old.setCustomerId("customer-2"); old.setCustomerTenantId("tenant-1");
        when(repository.findAll()).thenReturn(List.of(old));
        Jwt token = Jwt.withTokenValue("token").header("alg", "none").claim("roles", List.of("Cliente"))
            .claim("oid", "customer-1").claim("tid", "tenant-1").build();
        var result = new ProductApiController(repository, mock(ProductAuditPublisher.class)).list(token);
        assertEquals(1, result.size());
        assertEquals("Producto anterior", result.getFirst().name());
        verify(repository).findAll();
    }
    @Test void stockNoPuedeQuedarNegativo() {
        ProductoRepository repository = mock(ProductoRepository.class);
        Producto p = new Producto("A", "B", 100.0);
        p.setStock(0);
        when(repository.findById(1L)).thenReturn(Optional.of(p));
        var api = new ProductApiController(repository, mock(ProductAuditPublisher.class));
        assertThrows(ResponseStatusException.class, () -> api.stock(1L, new ProductApiController.StockInput(-1), null));
        verify(repository, never()).saveAndFlush(any());
    }
}
