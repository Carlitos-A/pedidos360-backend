package com.pedidos360.pedidos;

import com.pedidos360.pedidos.controller.AuditController;
import com.pedidos360.pedidos.controller.ReportsController;
import com.pedidos360.pedidos.model.AuditEvent;
import com.pedidos360.pedidos.model.Pedido;
import com.pedidos360.pedidos.repository.AuditEventRepository;
import com.pedidos360.pedidos.repository.PedidoRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportsAndAuditTest {
    @Test void ventasExcluyenCancelados() {
        PedidoRepository repository = mock(PedidoRepository.class);
        Pedido done = new Pedido("A", "A", 100.0, "COMPLETED");
        Pedido cancelled = new Pedido("B", "B", 500.0, "CANCELLED");
        done.setFecha(LocalDateTime.now()); cancelled.setFecha(LocalDateTime.now());
        when(repository.findAll()).thenReturn(List.of(done, cancelled));
        var result = new ReportsController(repository).kpis(30);
        assertEquals(100.0, result.totalSales());
        assertEquals(0.5, result.cancellationRate());
        assertNull(result.activeUsers());
    }

    @Test void auditoriaFiltraAntesDePaginar() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        when(repository.findAll()).thenReturn(List.of(
            new AuditEvent("one", "ORDER_CREATED", "Pedido creado", "a@test.cl", OffsetDateTime.now()),
            new AuditEvent("two", "PRODUCT_UPDATED", "Producto actualizado", "b@test.cl", OffsetDateTime.now())));
        var result = new AuditController(repository).list("a@test.cl", null, null, null, 0, 20);
        assertEquals(1, result.total());
        assertEquals("ORDER_CREATED", result.items().getFirst().type());
    }
}
