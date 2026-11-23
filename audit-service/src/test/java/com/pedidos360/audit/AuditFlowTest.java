package com.pedidos360.audit;

import com.pedidos360.audit.controller.AuditController;
import com.pedidos360.audit.messaging.AuditEventIngestor;
import com.pedidos360.audit.model.AuditEvent;
import com.pedidos360.audit.repository.AuditEventRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {"app.audit.kafka-enabled=false", "app.internal.key=test-key"})
@AutoConfigureMockMvc
class AuditFlowTest {
    @Autowired AuditEventIngestor ingestor;
    @Autowired AuditEventRepository repository;
    @Autowired AuditController controller;
    @Autowired MockMvc mvc;

    @Test void livenessPermiteHealthCheckSinTokenNiClave() throws Exception {
        mvc.perform(get("/internal/live"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("UP"));
    }
    @Autowired KafkaListenerEndpointRegistry listeners;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test void openApiPublicaSoloTimelineYBearer() throws Exception {
        var spec = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertTrue(spec.path("paths").has("/api/audit/events"));
        assertFalse(spec.path("paths").has("/internal/rejections/kafka"));
        assertEquals("bearer", spec.path("components").path("securitySchemes")
            .path("bearerAuth").path("scheme").asText());
        assertTrue(spec.path("paths").path("/api/audit/events").path("get")
            .path("responses").has("403"));
        assertTrue(spec.path("components").path("schemas").has("EventView"));
    }

    @Test @Transactional void consumeUnaVezYFiltraAntesDePaginarConDiaDeSantiago() {
        ingestor.ingest("pedidos360.audit.v1", """
            {"schemaVersion":1,"eventId":"order-created-42","type":"ORDER_CREATED",
             "entityId":"42","status":"CREATED","actorEmail":"Admin@Example.test",
             "occurredAt":"2026-11-20T03:01:00Z"}
            """);
        ingestor.ingest("pedidos360.audit.v1", """
            {"schemaVersion":1,"eventId":"order-created-42","type":"ORDER_CREATED",
             "entityId":"42","status":"CREATED","actorEmail":"Admin@Example.test",
             "occurredAt":"2026-11-20T03:01:00Z"}
            """);
        repository.saveAndFlush(new AuditEvent("old", "PRODUCT_UPDATED", "Producto 7 actualizado",
            "other@example.test", null, null, OffsetDateTime.parse("2026-11-20T02:59:00Z")));
        var page = controller.list("admin@example.test", "ORDER_CREATED",
            LocalDate.parse("2026-11-20"), LocalDate.parse("2026-11-20"), 0, 1);
        assertEquals(1, page.total());
        assertEquals(1, page.items().size());
        assertEquals("Pedido 42 creado", page.items().getFirst().description());
        assertEquals(2, repository.count());
        assertEquals(0, controller.list(null, null, LocalDate.parse("2026-11-19"),
            LocalDate.parse("2026-11-19"), 1, 20).items().size());
    }

    @Test @Transactional void loginSoloDesdeTopicoReservado() {
        String login = """
            {"schemaVersion":1,"eventId":"entra-signin-1","type":"USER_LOGIN","entityId":"signin-1",
             "actorEmail":"user@example.test","actorId":"oid-1","actorTenantId":"tid-1",
             "occurredAt":"2026-11-20T12:00:00Z"}
            """;
        assertThrows(AuditEventIngestor.InvalidAuditEventException.class,
            () -> ingestor.ingest("pedidos360.audit.v1", login));
        ingestor.ingest("pedidos360.login.v1", login);
        assertTrue(repository.existsByEventId("entra-signin-1"));
        assertThrows(AuditEventIngestor.InvalidAuditEventException.class,
            () -> ingestor.ingest("pedidos360.audit.v1", "{\"schemaVersion\":1,\"type\":\"PRODUCT_UPDATED\"}"));
    }

    @Test void consultaExigeAdmin() throws Exception {
        assertEquals(2, listeners.getListenerContainers().size());
        assertTrue(listeners.getListenerContainers().stream().noneMatch(container -> container.isRunning()));
        mvc.perform(get("/api/audit/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/audit/events").with(jwt().authorities(new SimpleGrantedAuthority("APPROLE_Cliente"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/audit/events").with(jwt().authorities(new SimpleGrantedAuthority("APPROLE_Admin"))))
            .andExpect(status().isOk());
        mvc.perform(get("/internal/rejections/kafka")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/rejections/kafka")
            .with(jwt().authorities(new SimpleGrantedAuthority("APPROLE_Cliente"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/internal/rejections/kafka")
            .with(jwt().authorities(new SimpleGrantedAuthority("APPROLE_Admin"))))
            .andExpect(status().isOk());
        mvc.perform(get("/internal/health")).andExpect(status().isForbidden());
        mvc.perform(get("/internal/health").header("X-Internal-Key", "test-key"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.service").value("audit"))
            .andExpect(jsonPath("$.status").value("UP"));
    }
}
