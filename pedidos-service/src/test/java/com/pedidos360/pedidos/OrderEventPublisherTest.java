package com.pedidos360.pedidos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.pedidos.messaging.OrderEventPublisher;
import com.pedidos360.pedidos.model.OrderActor;
import com.pedidos360.pedidos.model.OrderOutboxMessage;
import com.pedidos360.pedidos.model.Pedido;
import com.pedidos360.pedidos.model.PedidoItem;
import com.pedidos360.pedidos.model.PedidoStatusEvent;
import com.pedidos360.pedidos.repository.OrderOutboxRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false", "app.outbox.initial-delay-ms=600000"})
class OrderEventPublisherTest {
    @Autowired OrderEventPublisher publisher;
    @Autowired OrderOutboxRepository outbox;
    @Autowired TransactionTemplate transactions;
    @Autowired ObjectMapper mapper;
    @MockitoBean RabbitTemplate rabbit;

    @AfterEach void clean() { outbox.deleteAll(); }

    @Test void creaTresEntregasVersionadasEnLaMismaTransaccion() throws Exception {
        Pedido order = new Pedido("Cliente", "Detalle", 200.0, "CREATED");
        order.setId(42L); order.setCustomerId("owner"); order.setCustomerTenantId("tenant");
        order.setCreatedAtUtc(Instant.parse("2026-11-19T12:00:00Z"));
        order.setTotalClp(200L); order.setRequestActorEmail("admin@example.test");
        order.getItems().add(new PedidoItem("7", "Producto", 2, 100L));
        PedidoStatusEvent change = new PedidoStatusEvent(null, "CREATED",
            OffsetDateTime.ofInstant(order.getCreatedAtUtc(), ZoneOffset.UTC),
            new OrderActor("admin", "tenant", "Administrador", "Admin", "admin@example.test"));
        transactions.executeWithoutResult(tx -> publisher.enqueueCreated(order, change));
        assertEquals(3, outbox.count());
        var deliveries = outbox.findAll();
        assertEquals(1, deliveries.stream().map(OrderOutboxMessage::getEventId).distinct().count());
        var payload = mapper.readTree(deliveries.getFirst().getPayload());
        assertEquals(2, payload.path("schemaVersion").asInt());
        assertEquals("ORDER_CREATED", payload.path("eventType").asText());
        assertEquals("owner", payload.path("customerId").asText());
        assertEquals("admin", payload.path("actorId").asText());
        assertEquals(200L, payload.path("total").asLong());
        assertEquals(2, payload.path("items").get(0).path("quantity").asInt());

        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(tx -> {
            publisher.enqueueCreated(order, change);
            throw new IllegalStateException("rollback");
        }));
        assertEquals(3, outbox.count());
    }

    @Test void rechazoDelBrokerQuedaPendienteYConfirmacionLoCierra() {
        OrderOutboxMessage entry = outbox.saveAndFlush(new OrderOutboxMessage("event-1", "exchange", "route", "{}"));
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "unavailable"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(), any(CorrelationData.class));
        publisher.dispatchPending();
        var pending = outbox.findById(entry.getId()).orElseThrow();
        assertNull(pending.getPublishedAt());
        assertEquals(1, pending.getAttempts());

        reset(rabbit);
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(), any(CorrelationData.class));
        publisher.dispatchPending();
        assertNotNull(outbox.findById(entry.getId()).orElseThrow().getPublishedAt());
        verify(rabbit).send(eq("exchange"), eq("route"), any(), any(CorrelationData.class));
    }

    @Test void cancelacionPublicaEstadoPrevioYActor() throws Exception {
        Pedido order = new Pedido("Cliente", "Detalle", 200.0, "CANCELLED");
        order.setId(52L); order.setCustomerId("owner"); order.setCustomerTenantId("tenant");
        order.setTotalClp(200L);
        PedidoStatusEvent change = new PedidoStatusEvent("CREATED", "CANCELLED",
            OffsetDateTime.parse("2026-11-19T12:00:00Z"),
            new OrderActor("admin", "tenant", "Administrador", "Admin", "admin@example.test"));
        transactions.executeWithoutResult(tx -> publisher.enqueueStatus(order, change,
            "order-status-52-2", "admin@example.test"));
        var entry = outbox.findAll().getFirst();
        var payload = mapper.readTree(entry.getPayload());
        assertEquals("orders.cancelled", entry.getRoutingKey());
        assertEquals("ORDER_CANCELLED", payload.path("eventType").asText());
        assertEquals("CREATED", payload.path("previousStatus").asText());
        assertEquals("CANCELLED", payload.path("status").asText());
        assertEquals("admin", payload.path("actorId").asText());
    }
}
