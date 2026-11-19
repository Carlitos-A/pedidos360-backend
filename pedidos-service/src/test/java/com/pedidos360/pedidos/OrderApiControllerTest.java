package com.pedidos360.pedidos;

import com.pedidos360.pedidos.controller.OrderApiController;
import com.pedidos360.pedidos.inventory.OrderWriteService;
import com.pedidos360.pedidos.messaging.OrderEventPublisher;
import com.pedidos360.pedidos.model.Pedido;
import com.pedidos360.pedidos.repository.PedidoRepository;
import com.pedidos360.pedidos.repository.AuditEventRepository;
import com.pedidos360.pedidos.identity.CustomerDirectory;
import com.pedidos360.pedidos.repository.VerifiedOwnerMappingRepository;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderApiControllerTest {
    private final PedidoRepository repository = mock(PedidoRepository.class);
    private final CustomerDirectory directory = mock(CustomerDirectory.class);
    private final OrderApiController api = new OrderApiController(repository, mock(OrderWriteService.class), mock(AuditEventRepository.class), directory, mock(VerifiedOwnerMappingRepository.class), mock(OrderEventPublisher.class));
    private final Jwt cliente = Jwt.withTokenValue("token").header("alg", "none")
        .claim("roles", List.of("Cliente")).claim("oid", "owner-1").claim("tid", "tenant-1").build();
    private final Jwt admin = Jwt.withTokenValue("admin").header("alg", "none")
        .claim("roles", List.of("Admin")).claim("oid", "admin-1").claim("tid", "tenant-1").build();

    @Test void clienteSoloVeSusPedidosYTotalNoFiltraOtros() {
        Pedido own = pedido(1L, "owner-1");
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(own), PageRequest.of(0, 10), 1));
        var result = api.list(cliente, null, null, null, null, 0, 10);
        assertEquals(1, result.total());
        assertEquals("1", result.items().getFirst().id());
        verify(repository, never()).findAll();
    }

    @Test void detalleAjenoResponde404() {
        when(repository.findById(2L)).thenReturn(Optional.of(pedido(2L, "owner-2")));
        assertThrows(ResponseStatusException.class, () -> api.get(2L, cliente));
    }

    @Test void noAceptaPreciosFraccionariosNiPedidosNuevosSinItems() {
        var fraction = new OrderApiController.OrderInput("Cliente", "owner-1", "tenant-1", "Detalle",
            BigDecimal.ZERO, List.of(new OrderApiController.OrderItemInput("7", "Producto", 1,
                new BigDecimal("100.5"))), java.util.UUID.randomUUID().toString(), null);
        var empty = new OrderApiController.OrderInput("Cliente", "owner-1", "tenant-1", "Detalle",
            BigDecimal.ZERO, List.of(), java.util.UUID.randomUUID().toString(), null);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> api.create(fraction, admin)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> api.create(empty, admin)).getStatusCode());
        verifyNoInteractions(directory);
    }

    @Test void versionAnteriorDa409SinModificarElPedido() {
        Pedido p = pedido(3L, "owner-1");
        ReflectionTestUtils.setField(p, "version", 2L);
        when(repository.findById(3L)).thenReturn(Optional.of(p));
        var error = assertThrows(ResponseStatusException.class,
            () -> api.status(3L, new OrderApiController.StatusInput("ACCEPTED", 1L), admin));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("CREATED", p.getEstado());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void estadoHistoricoDesconocidoQuedaVisiblePeroNoPuedeAvanzar() {
        Pedido p = pedido(4L, "owner-1");
        p.setEstado("DESPACHADO");
        ReflectionTestUtils.setField(p, "version", 0L);
        when(repository.findById(4L)).thenReturn(Optional.of(p));
        assertEquals("DESPACHADO", api.get(4L, admin).status());
        assertTrue(api.get(4L, admin).history().isEmpty());
        var error = assertThrows(ResponseStatusException.class,
            () -> api.status(4L, new OrderApiController.StatusInput("COMPLETED", 0L), admin));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    @Test void cambioValidoDevuelveRecursoConVersion() {
        Pedido p = pedido(5L, "owner-1");
        ReflectionTestUtils.setField(p, "version", 0L);
        when(repository.findById(5L)).thenReturn(Optional.of(p));
        var result = api.status(5L, new OrderApiController.StatusInput("ACCEPTED", 0L), admin);
        assertEquals("ACCEPTED", result.status());
        assertEquals(0L, result.version());
        assertEquals("ACCEPTED", p.getEstado());
        assertEquals("CREATED", result.history().getFirst().getPreviousStatus());
        assertEquals("admin-1", result.history().getFirst().getActorId());
        verify(repository).saveAndFlush(p);
    }

    private Pedido pedido(Long id, String owner) {
        Pedido p = new Pedido("Cliente", "Detalle", 100.0, "CREATED");
        p.setId(id); p.setCustomerId(owner); p.setCustomerTenantId("tenant-1");
        return p;
    }
}
