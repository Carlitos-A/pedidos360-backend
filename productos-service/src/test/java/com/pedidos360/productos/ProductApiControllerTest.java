package com.pedidos360.productos;

import com.pedidos360.productos.controller.ProductApiController;
import com.pedidos360.productos.model.Producto;
import com.pedidos360.productos.repository.ProductoRepository;
import com.pedidos360.productos.messaging.ProductAuditPublisher;
import java.util.Optional;
import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductApiControllerTest {
    @Test void clienteConsultaSoloPropietarioYTenantDelToken() {
        ProductoRepository repository = mock(ProductoRepository.class);
        Producto own = new Producto("Mío", "A", 100.0);
        own.setCustomerId("customer-1"); own.setCustomerTenantId("tenant-1");
        when(repository.findByCustomerIdAndCustomerTenantId("customer-1", "tenant-1")).thenReturn(List.of(own));
        Jwt token = Jwt.withTokenValue("token").header("alg", "none").claim("roles", List.of("Cliente"))
            .claim("oid", "customer-1").claim("tid", "tenant-1").build();
        var result = new ProductApiController(repository, mock(ProductAuditPublisher.class)).list(token);
        assertEquals(1, result.size());
        verify(repository, never()).findAll();
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
