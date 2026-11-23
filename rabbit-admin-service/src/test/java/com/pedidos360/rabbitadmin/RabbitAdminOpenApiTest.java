package com.pedidos360.rabbitadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.rabbitadmin.service.RabbitAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RabbitAdminOpenApiTest {
    @Autowired MockMvc mvc;

    @Test void livenessPermiteHealthCheckSinTokenNiClave() throws Exception {
        mvc.perform(get("/internal/live"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("UP"));
    }
    @MockitoBean JwtDecoder decoder;
    @MockitoBean RabbitAdminService service;

    @Test void documentaRutasAdministrativasYBearer() throws Exception {
        var spec = new ObjectMapper().readTree(mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(spec.path("paths").has("/admin/queues"));
        assertTrue(spec.path("paths").has("/admin/exchanges"));
        assertTrue(spec.path("paths").has("/admin/bindings"));
        assertEquals("bearer", spec.path("components").path("securitySchemes")
            .path("bearerAuth").path("scheme").asText());
        assertFalse(spec.path("paths").path("/admin/queues").path("post")
            .path("responses").has("409"));
        assertTrue(spec.path("components").path("schemas").has("CreateQueueRequest"));
    }
}
