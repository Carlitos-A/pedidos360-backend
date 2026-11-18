package com.pedidos360.pedidos;

import com.pedidos360.pedidos.controller.OrderApiController;
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
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderApiControllerTest {
    private final PedidoRepository repository = mock(PedidoRepository.class);
    private final CustomerDirectory directory = mock(CustomerDirectory.class);
    private final OrderApiController api = new OrderApiController(repository, mock(OrderEventPublisher.class), mock(AuditEventRepository.class), directory, mock(VerifiedOwnerMappingRepository.class));
    private final Jwt cliente = Jwt.withTokenValue("token").header("alg", "none")
        .claim("roles", List.of("Cliente")).claim("oid", "owner-1").claim("tid", "tenant-1").build();

    @Test void clienteSoloVeSusPedidosYTotalNoFiltraOtros() {
        Pedido own = pedido(1L, "owner-1");
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(own), PageRequest.of(0, 10), 1));
        var result = api.list(cliente, null, null, null, null, 0, 10);
        assertEquals(1, result.total());
        assertEquals("1", result.items().getFirst().id());
        verify(repository, never()).findAll();
    }

    @Test void putNoPuedeReasignarPropietario() {
        when(repository.findById(1L)).thenReturn(Optional.of(pedido(1L, "owner-1")));
        var input = new OrderApiController.OrderInput("Cliente", "owner-2", "tenant-1", "Detalle", 100L, List.of());
        assertThrows(ResponseStatusException.class, () -> api.update(1L, input));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void detalleAjenoResponde404() {
        when(repository.findById(2L)).thenReturn(Optional.of(pedido(2L, "owner-2")));
        assertThrows(ResponseStatusException.class, () -> api.get(2L, cliente));
    }

    private Pedido pedido(Long id, String owner) {
        Pedido p = new Pedido("Cliente", "Detalle", 100.0, "CREATED");
        p.setId(id); p.setCustomerId(owner); p.setCustomerTenantId("tenant-1");
        return p;
    }
}
