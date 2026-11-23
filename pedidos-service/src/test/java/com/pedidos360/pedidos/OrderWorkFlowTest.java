package com.pedidos360.pedidos;

import com.pedidos360.pedidos.messaging.OrderEventV2;
import com.pedidos360.pedidos.messaging.OrderWorkEvent;
import com.pedidos360.pedidos.messaging.OrderWorkService;
import com.pedidos360.pedidos.model.Pedido;
import com.pedidos360.pedidos.repository.OrderNotificationRepository;
import com.pedidos360.pedidos.repository.OrderTicketRepository;
import com.pedidos360.pedidos.repository.PedidoRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false",
    "app.optional-work.enabled=false"})
@AutoConfigureMockMvc
class OrderWorkFlowTest {
    @Autowired PedidoRepository orders;
    @Autowired OrderNotificationRepository notifications;
    @Autowired OrderTicketRepository tickets;
    @Autowired OrderWorkService work;
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;

    @Test @Transactional void persisteUnaVezYAislaPorPropietarioActual() throws Exception {
        Pedido own = order("owner");
        Pedido other = order("other");
        OrderWorkEvent ownEvent = event(own.getId(), "owner");
        OrderWorkEvent otherEvent = event(other.getId(), "other");
        work.notifyCustomer(ownEvent); work.notifyCustomer(ownEvent);
        work.issueTicket(ownEvent); work.issueTicket(ownEvent);
        work.notifyCustomer(otherEvent);
        assertEquals(2, notifications.count());
        assertEquals(1, tickets.count());

        var client = jwt().jwt(token -> token.claim("roles", List.of("Cliente"))
            .claim("oid", "owner").claim("tid", "tenant"))
            .authorities(new SimpleGrantedAuthority("APPROLE_Cliente"));
        mvc.perform(get("/api/notifications").with(client)).andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].orderId").value(own.getId()));
        mvc.perform(get("/api/orders/" + own.getId() + "/ticket").with(client))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ticketNumber").value("P360-" + own.getId()))
            .andExpect(jsonPath("$.total").value(2500))
            .andExpect(jsonPath("$.items[0].unitPrice").value(1250));
        mvc.perform(get("/api/orders/" + own.getId() + "/ticket")
            .with(jwt().jwt(token -> token.claim("roles", List.of("Cliente"))
                .claim("oid", "other").claim("tid", "tenant"))
                .authorities(new SimpleGrantedAuthority("APPROLE_Cliente"))))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/notifications").with(jwt().jwt(token -> token.claim("roles", List.of("Admin")))
            .authorities(new SimpleGrantedAuthority("APPROLE_Admin"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        own.setCustomerId("other");
        orders.saveAndFlush(own);
        mvc.perform(get("/api/notifications").with(client)).andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/orders/" + own.getId() + "/ticket").with(client))
            .andExpect(status().isNotFound());
    }

    private Pedido order(String owner) {
        Pedido order = new Pedido("Cliente", "Detalle", 2500d, "CREATED");
        order.setCustomerId(owner); order.setCustomerTenantId("tenant");
        return orders.saveAndFlush(order);
    }
    private OrderWorkEvent event(long orderId, String owner) {
        Instant date = Instant.parse("2026-11-21T12:00:00Z");
        return new OrderWorkEvent(UUID.randomUUID().toString(), orderId, "Cliente", owner,
            "tenant", 2500, date, date, List.of(new OrderEventV2.Line("9", "Producto", 2, 1250L)));
    }
}
