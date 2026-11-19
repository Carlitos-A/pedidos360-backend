package com.pedidos360.pedidos;

import com.pedidos360.pedidos.identity.CustomerDirectory.Customer;
import com.pedidos360.pedidos.controller.OrderApiController;
import com.pedidos360.pedidos.inventory.InventoryClient;
import com.pedidos360.pedidos.inventory.OrderWriteService;
import com.pedidos360.pedidos.messaging.OrderEventPublisher;
import com.pedidos360.pedidos.model.PedidoItem;
import com.pedidos360.pedidos.repository.PedidoRepository;
import com.pedidos360.pedidos.repository.PendingInventoryReleaseRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "app.inventory.initial-delay-ms=600000"})
class OrderInventoryTest {
    @Autowired OrderWriteService writes;
    @Autowired PedidoRepository orders;
    @Autowired PendingInventoryReleaseRepository releases;
    @Autowired OrderApiController api;
    @MockitoBean InventoryClient inventory;
    @MockitoBean OrderEventPublisher publisher;
    private final Customer customer = new Customer("owner", "tenant", "Cliente", "cliente@example.test");

    @Test void totalYNombreProvienenDelCatalogoYReintentarNoDuplica() {
        String requestId = UUID.randomUUID().toString();
        when(inventory.reserve(eq(requestId), eq("owner"), eq("tenant"), anyList(), eq(true)))
            .thenReturn(new InventoryClient.Quote(List.of(new InventoryClient.QuotedLine("7", "Real", 2, 150L)), 300L))
            .thenReturn(new InventoryClient.Quote(List.of(new InventoryClient.QuotedLine("7", "Real", 3, 150L)), 450L));
        var input = new OrderWriteService.Input(requestId, null, "Detalle", List.of(new PedidoItem("7", "Falso", 2, 99L)));
        var created = writes.create(input, customer, "Admin", "admin@example.test");
        assertEquals(300L, created.getTotalClp());
        assertEquals("Real", created.getItems().getFirst().getName());
        assertNotNull(created.getCreatedAtUtc());
        assertEquals("READY", created.getInventoryState());
        assertEquals(created.getId(), writes.create(input, customer, "Admin", "admin@example.test").getId());
        verify(inventory, times(1)).reserve(eq(requestId), eq("owner"), eq("tenant"), anyList(), eq(true));
        var changed = writes.update(created.getId(), new OrderWriteService.Input(null, created.getVersion(), "Editado",
            List.of(new PedidoItem("7", "Falso", 3, 99L))), "owner", "tenant");
        assertEquals(450L, changed.getTotalClp());
        assertEquals("CREATED", changed.getEstado());
        assertEquals(3, changed.getItems().getFirst().getQuantity());
        verify(inventory, times(2)).reserve(eq(requestId), eq("owner"), eq("tenant"), anyList(), eq(true));
    }

    @Test void caidaDelCatalogoDejaPedidoPendienteYReintentoLoConfirma() {
        String requestId = UUID.randomUUID().toString();
        when(inventory.reserve(eq(requestId), anyString(), anyString(), anyList(), eq(true)))
            .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
            .thenReturn(new InventoryClient.Quote(List.of(new InventoryClient.QuotedLine("7", "Real", 1, 100L)), 100L));
        var input = new OrderWriteService.Input(requestId, null, "Detalle", List.of(new PedidoItem("7", "Real", 1, 100L)));
        assertThrows(ResponseStatusException.class, () -> writes.create(input, customer, "Admin", "admin@example.test"));
        assertEquals("PENDING", orders.findByReservationKey(requestId).orElseThrow().getInventoryState());
        var ready = writes.create(input, customer, "Admin", "admin@example.test");
        assertEquals("READY", ready.getInventoryState());
        assertEquals(1, orders.findByReservationKey(requestId).stream().count());
    }

    @Test void cancelarLiberaLaReservaDespuesDeConfirmarElEstado() {
        String requestId = UUID.randomUUID().toString();
        when(inventory.reserve(eq(requestId), anyString(), anyString(), anyList(), eq(true)))
            .thenReturn(new InventoryClient.Quote(List.of(new InventoryClient.QuotedLine("7", "Real", 1, 100L)), 100L));
        when(inventory.reserve(eq(requestId), anyString(), anyString(), eq(List.of()), eq(false)))
            .thenReturn(new InventoryClient.Quote(List.of(), 0L));
        var input = new OrderWriteService.Input(requestId, null, "Detalle", List.of(new PedidoItem("7", "Real", 1, 100L)));
        var created = writes.create(input, customer, "Admin", "admin@example.test");
        Jwt admin = Jwt.withTokenValue("admin").header("alg", "none")
            .claim("roles", List.of("Admin")).claim("oid", "admin-1").build();
        var cancelled = api.status(created.getId(), new OrderApiController.StatusInput("CANCELLED", created.getVersion()), admin);
        assertEquals("CANCELLED", cancelled.status());
        verify(inventory).reserve(eq(requestId), eq("owner"), eq("tenant"), eq(List.of()), eq(false));
        assertFalse(releases.existsById(created.getId()));
        var current = orders.findById(created.getId()).orElseThrow();
        var edited = writes.update(current.getId(), new OrderWriteService.Input(null, current.getVersion(),
            "Corrección del detalle", List.of(new PedidoItem("7", "Real", 1, 100L))), "owner", "tenant");
        assertEquals("CANCELLED", edited.getEstado());
        assertEquals("Corrección del detalle", edited.getDetalle());
    }
}
