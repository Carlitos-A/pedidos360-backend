package com.pedidos360.productos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.productos.messaging.ProductAuditOutbox;
import com.pedidos360.productos.messaging.ProductAuditOutboxRepository;
import com.pedidos360.productos.messaging.ProductAuditPublisher;
import com.pedidos360.productos.model.Producto;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KafkaProductAuditPublisherTest {
    @Test void rechazaOperacionSinIdentidadJwt() {
        ProductAuditOutboxRepository outbox = mock(ProductAuditOutboxRepository.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var publisher = new ProductAuditPublisher(mock(RabbitTemplate.class), kafka, outbox,
            true, "pedidos360.audit.v1");
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
            .claim("preferred_username", "admin@example.test").build();
        assertThrows(ResponseStatusException.class,
            () -> publisher.updated(new Producto("A", "B", 100.0), jwt));
        verifyNoInteractions(outbox);
    }

    @Test void productoUsaIdentidadDelJwtYEsperaConfirmacion() throws Exception {
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        ProductAuditOutboxRepository outbox = mock(ProductAuditOutboxRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        var publisher = new ProductAuditPublisher(rabbit, kafka, outbox, true, "pedidos360.audit.v1");
        Producto product = new Producto("Nombre privado", "Categoría", 100.0);
        product.setId(7L);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("oid", "admin-id")
            .claim("tid", "tenant-id").claim("preferred_username", "admin@example.test").build();
        publisher.updated(product, jwt);
        var captor = org.mockito.ArgumentCaptor.forClass(ProductAuditOutbox.class);
        verify(outbox).save(captor.capture());
        ProductAuditOutbox event = captor.getValue();
        assertEquals("admin-id", event.getActorId());
        assertEquals("tenant-id", event.getActorTenantId());
        assertEquals("kafka", event.getDestination());
        when(outbox.findTop100ByOrderByOccurredAtAsc()).thenReturn(List.of(event));
        when(kafka.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
        publisher.publishPending();
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("pedidos360.audit.v1"), eq("product:7"), payload.capture());
        var json = new ObjectMapper().readTree(payload.getValue());
        assertEquals("PRODUCT_UPDATED", json.path("type").asText());
        assertEquals("admin@example.test", json.path("actorEmail").asText());
        assertFalse(json.has("name"));
        verify(outbox).delete(event);
        verifyNoInteractions(rabbit);
    }

    @Test void falloKafkaConservaElEvento() {
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        ProductAuditOutboxRepository outbox = mock(ProductAuditOutboxRepository.class);
        var publisher = new ProductAuditPublisher(mock(RabbitTemplate.class), kafka, outbox, true, "pedidos360.audit.v1");
        ProductAuditOutbox event = new ProductAuditOutbox("event-1", "7", "admin@example.test", null, null,
            java.time.OffsetDateTime.parse("2026-11-20T12:00:00Z"), "kafka");
        when(outbox.findTop100ByOrderByOccurredAtAsc()).thenReturn(List.of(event));
        when(kafka.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        publisher.publishPending();
        verify(outbox, never()).delete(any());
    }
}
