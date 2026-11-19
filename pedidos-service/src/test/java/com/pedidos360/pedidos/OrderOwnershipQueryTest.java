package com.pedidos360.pedidos;

import com.pedidos360.pedidos.controller.OrderApiController;
import com.pedidos360.pedidos.model.Pedido;
import com.pedidos360.pedidos.repository.PedidoRepository;
import jakarta.transaction.Transactional;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class OrderOwnershipQueryTest {
    @Autowired PedidoRepository repository;
    @Autowired OrderApiController api;

    @Test @Transactional void paginaYTotalIncluyenSoloPropietarioDelToken() {
        Pedido mine = new Pedido("Mío", "Detalle", 100.0, "CREATED");
        mine.setCustomerId("owner-1"); mine.setCustomerTenantId("tenant-1");
        repository.saveAndFlush(mine);
        Pedido other = new Pedido("Otro", "Detalle", 200.0, "CREATED");
        other.setCustomerId("owner-2"); other.setCustomerTenantId("tenant-1");
        repository.saveAndFlush(other);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("roles", List.of("Cliente"))
            .claim("oid", "owner-1").claim("tid", "tenant-1").build();
        var page = api.list(jwt, "CREATED", null, null, null, 0, 1);
        assertEquals(1, page.total());
        assertEquals(String.valueOf(mine.getId()), page.items().getFirst().id());
        var byId = api.list(jwt, null, String.valueOf(mine.getId()), null, null, 0, 10);
        assertEquals(1, byId.total());
    }

    @Test @Transactional void versionPersistidaImpideUnSegundoCambioObsoleto() {
        Pedido p = new Pedido("Cliente", "Detalle", 100.0, "CREATED");
        p.setCustomerId("owner-1"); p.setCustomerTenantId("tenant-1");
        repository.saveAndFlush(p);
        Long initial = p.getVersion();
        Jwt admin = Jwt.withTokenValue("admin").header("alg", "none")
            .claim("roles", List.of("Admin")).claim("oid", "admin-1").build();
        var changed = api.status(p.getId(), new OrderApiController.StatusInput("ACCEPTED", initial), admin);
        assertEquals("ACCEPTED", changed.status());
        assertEquals(initial + 1, changed.version());
        var conflict = assertThrows(ResponseStatusException.class,
            () -> api.status(p.getId(), new OrderApiController.StatusInput("CANCELLED", initial), admin));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
    }
}
