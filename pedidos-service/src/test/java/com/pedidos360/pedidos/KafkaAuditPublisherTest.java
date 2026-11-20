package com.pedidos360.pedidos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.pedidos.messaging.OrderEventPublisher;
import com.pedidos360.pedidos.model.*;
import com.pedidos360.pedidos.repository.OrderOutboxRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KafkaAuditPublisherTest {
    @Test void estadoVaPorKafkaConPayloadMinimoYSeCierraSoloTrasConfirmar() throws Exception {
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        OrderOutboxRepository outbox = mock(OrderOutboxRepository.class);
        var publisher = new OrderEventPublisher(rabbit, kafka, new ObjectMapper(), outbox,
            "direct", "topic", "orders.created", "orders.created.ticket", "orders.created.audit",
            "orders.status.changed", "orders.cancelled", true, "pedidos360.audit.v1");
        Pedido order = new Pedido("Nombre privado", "Detalle privado", 200.0, "CANCELLED");
        order.setId(52L); order.setCustomerId("owner"); order.setCustomerTenantId("tenant");
        order.getItems().add(new PedidoItem("7", "Producto privado", 2, 100L));
        PedidoStatusEvent change = new PedidoStatusEvent("CREATED", "CANCELLED",
            OffsetDateTime.parse("2026-11-20T12:00:00Z"),
            new OrderActor("admin", "tenant", "Administrador", "Admin", "admin@example.test"));
        publisher.enqueueStatus(order, change, "order-status-52-2", "admin@example.test");
        var captor = org.mockito.ArgumentCaptor.forClass(OrderOutboxMessage.class);
        verify(outbox).save(captor.capture());
        OrderOutboxMessage entry = captor.getValue();
        assertEquals("kafka", entry.getExchangeName());
        assertEquals("order-status-52-2", entry.getEventId());
        var payload = new ObjectMapper().readTree(entry.getPayload());
        assertEquals("ORDER_CANCELLED", payload.path("type").asText());
        assertEquals("admin", payload.path("actorId").asText());
        assertFalse(payload.has("detail"));
        assertFalse(payload.has("customerName"));
        assertFalse(payload.has("items"));
        when(outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(entry));
        when(kafka.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
        publisher.dispatchPending();
        verify(kafka).send(eq("pedidos360.audit.v1"), eq("order:52"), eq(entry.getPayload()));
        assertNotNull(entry.getPublishedAt());
        verifyNoInteractions(rabbit);
    }

    @Test void falloKafkaDejaElOutboxPendiente() {
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OrderOutboxRepository outbox = mock(OrderOutboxRepository.class);
        var publisher = new OrderEventPublisher(mock(RabbitTemplate.class), kafka, new ObjectMapper(), outbox,
            "direct", "topic", "created", "ticket", "audit", "status", "cancelled", true, "pedidos360.audit.v1");
        var entry = new OrderOutboxMessage("event-1", "kafka", "pedidos360.audit.v1", "{\"entityId\":\"1\"}");
        var later = new OrderOutboxMessage("event-2", "kafka", "pedidos360.audit.v1", "{\"entityId\":\"1\"}");
        when(outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(entry, later));
        when(kafka.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        publisher.dispatchPending();
        assertNull(entry.getPublishedAt());
        assertEquals(1, entry.getAttempts());
        assertEquals(0, later.getAttempts());
        verify(outbox).saveAndFlush(entry);
        verify(kafka, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test void cambioDePropietarioConservaLaClaveDeMigracion() throws Exception {
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OrderOutboxRepository outbox = mock(OrderOutboxRepository.class);
        var publisher = new OrderEventPublisher(mock(RabbitTemplate.class), kafka, new ObjectMapper(), outbox,
            "direct", "topic", "created", "ticket", "audit", "status", "cancelled", true, "pedidos360.audit.v1");
        Pedido order = new Pedido("Cliente", "Detalle", 100.0, "CREATED");
        order.setId(42L);
        order.setUpdatedAt(OffsetDateTime.parse("2026-11-20T12:00:00Z"));
        publisher.enqueueOwnerChanged(order, "order-owner-migrated-42",
            new OrderActor("admin", "tenant", "Admin", "Admin", "admin@example.test"));
        var captor = org.mockito.ArgumentCaptor.forClass(OrderOutboxMessage.class);
        verify(outbox).save(captor.capture());
        assertEquals("order-owner-migrated-42", captor.getValue().getEventId());
        var json = new ObjectMapper().readTree(captor.getValue().getPayload());
        assertEquals("ORDER_OWNER_CHANGED", json.path("type").asText());
        assertFalse(json.has("customerId"));
    }
}
