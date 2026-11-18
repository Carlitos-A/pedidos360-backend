package com.pedidos360.pedidos;

import com.pedidos360.pedidos.controller.OrderApiController;
import com.pedidos360.pedidos.messaging.OrderEventPublisher;
import com.pedidos360.pedidos.model.Pedido;
import com.pedidos360.pedidos.repository.PedidoRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderApiControllerTest {
    private final PedidoRepository repository = mock(PedidoRepository.class);
    private final OrderApiController api = new OrderApiController(repository, mock(OrderEventPublisher.class));
    private final Jwt cliente = Jwt.withTokenValue("token").header("alg", "none")
        .claim("roles", List.of("Cliente")).claim("oid", "owner-1").claim("tid", "tenant-1").build();

    @Test void clienteSoloVeSusPedidosYTotalNoFiltraOtros() {
        Pedido own = pedido(1L, "owner-1");
        Pedido other = pedido(2L, "owner-2");
        when(repository.findAll()).thenReturn(List.of(own, other));
        var result = api.list(cliente, null, null, null, null, 0, 10);
        assertEquals(1, result.total());
        assertEquals("1", result.items().getFirst().id());
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
