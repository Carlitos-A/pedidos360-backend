package com.pedidos360.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.report.controller.ReportsController;
import com.pedidos360.report.messaging.ReportOrderEvent;
import com.pedidos360.report.messaging.ReportProjectionWriter;
import com.pedidos360.report.repository.ReportOrderRepository;
import com.pedidos360.report.repository.ReportReceiptRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {"app.report.kafka-enabled=false", "app.internal.key=test-key"})
@AutoConfigureMockMvc
class ReportProjectionTest {
    @Autowired ReportProjectionWriter writer;
    @Autowired ReportOrderRepository orders;
    @Autowired ReportReceiptRepository receipts;
    @Autowired ReportsController reports;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;

    @Test void livenessPermiteHealthCheckSinTokenNiClave() throws Exception {
        mvc.perform(get("/internal/live"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("UP"));
    }
    @MockitoBean JwtDecoder decoder;

    @Test void healthDeReportesDetectaKafkaSinParticionesCuandoEstaActivo() {
        var repository = org.mockito.Mockito.mock(ReportOrderRepository.class);
        @SuppressWarnings("unchecked")
        var kafka = (org.springframework.kafka.core.KafkaTemplate<String, String>)
            org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
        org.mockito.Mockito.when(kafka.partitionsFor("orders.events")).thenReturn(java.util.List.of());
        var health = new com.pedidos360.report.controller.InternalHealthController(
            repository, kafka, "test-key", true, "orders.events");
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> health.check("test-key"));
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }

    @Test void openApiPublicaCuatroReportesYBearer() throws Exception {
        var spec = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        for (String path : java.util.List.of("/api/reports/kpis", "/api/reports/sales",
            "/api/reports/lead-time", "/api/reports/top-products"))
            assertTrue(spec.path("paths").has(path), path);
        assertFalse(spec.path("paths").has("/internal/health"));
        assertEquals("bearer", spec.path("components").path("securitySchemes")
            .path("bearerAuth").path("scheme").asText());
        assertTrue(spec.path("paths").path("/api/reports/kpis").path("get")
            .path("responses").has("401"));
        assertTrue(spec.path("components").path("schemas").has("Kpis"));
    }

    @Test @Transactional void proyectaCreacionYFinalizacionSinDuplicarNiRegresarRevision() throws Exception {
        Instant created = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant completed = created.plus(12, ChronoUnit.HOURS);
        writer.apply(ReportOrderEvent.parse(mapper, payload("event-1", 1, "CREATED", created, created)));
        writer.apply(ReportOrderEvent.parse(mapper, payload("event-2", 2, "COMPLETED", created, completed)));
        writer.apply(ReportOrderEvent.parse(mapper, payload("event-2", 2, "COMPLETED", created, completed)));
        writer.apply(ReportOrderEvent.parse(mapper, payload("event-old", 1, "CREATED", created, created)));
        assertEquals(1, orders.count());
        assertEquals(3, receipts.count());
        assertEquals("COMPLETED", orders.findById(42L).orElseThrow().getStatus());
        var kpis = reports.kpis(30);
        assertEquals(1, kpis.totalOrders());
        assertEquals(200, kpis.totalSales());
        assertEquals(1, kpis.completedOrders());
        assertEquals(0.5, kpis.averageLeadTimeDays());
        assertNull(kpis.activeUsers());
        assertNotNull(kpis.updatedAt());
        assertEquals(2, reports.topProducts(30).getFirst().units());
        assertEquals(1, reports.sales(30).getFirst().orderCount());
    }

    @Test void rechazaEventoConTotalIncoherente() {
        Instant now = Instant.now();
        String bad = payload("event-bad", 1, "CREATED", now, now).replace("\"total\":200", "\"total\":201");
        assertThrows(ReportOrderEvent.InvalidEvent.class, () -> ReportOrderEvent.parse(mapper, bad));
    }

    @Test void lecturaExigeAdminYHealthExigeClave() throws Exception {
        mvc.perform(get("/api/reports/kpis")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reports/kpis").with(jwt().authorities(new SimpleGrantedAuthority("APPROLE_Cliente"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/kpis").with(jwt().authorities(new SimpleGrantedAuthority("APPROLE_Admin"))))
            .andExpect(status().isOk());
        mvc.perform(get("/internal/health")).andExpect(status().isForbidden());
        mvc.perform(get("/internal/health").header("X-Internal-Key", "test-key"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.service").value("report"))
            .andExpect(jsonPath("$.status").value("UP"));
    }

    private String payload(String eventId, long revision, String status, Instant created, Instant occurred) {
        return """
            {"schemaVersion":1,"eventId":"%s","type":"ORDER_SNAPSHOT","orderId":"42",
             "revision":%d,"occurredAt":"%s","createdAt":"%s","status":"%s","total":200,
             "items":[{"productId":"7","name":"Producto","quantity":2,"unitPrice":100}]}
            """.formatted(eventId, revision, occurred, created, status);
    }
}
