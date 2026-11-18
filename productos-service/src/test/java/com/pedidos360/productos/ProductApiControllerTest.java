package com.pedidos360.productos;

import com.pedidos360.productos.controller.ProductApiController;
import com.pedidos360.productos.model.Producto;
import com.pedidos360.productos.repository.ProductoRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductApiControllerTest {
    @Test void stockNoPuedeQuedarNegativo() {
        ProductoRepository repository = mock(ProductoRepository.class);
        Producto p = new Producto("A", "B", 100.0);
        p.setStock(0);
        when(repository.findById(1L)).thenReturn(Optional.of(p));
        var api = new ProductApiController(repository);
        assertThrows(ResponseStatusException.class, () -> api.stock(1L, new ProductApiController.StockInput(-1)));
        verify(repository, never()).saveAndFlush(any());
    }
}
