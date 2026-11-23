package com.pedidos360.kafkaadmin;

import com.pedidos360.kafkaadmin.service.KafkaManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.internal.key=test-internal-key", "logging.level.org.apache.kafka=ERROR"})
@AutoConfigureMockMvc
class KafkaAdminSecurityTest {
    @Autowired MockMvc mvc;

    @Test void livenessPermiteHealthCheckSinTokenNiClave() throws Exception {
        mvc.perform(get("/internal/live"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("UP"));
    }
    @MockitoBean JwtDecoder decoder;
    @MockitoBean KafkaManagementService management;
    @Autowired JwtAuthenticationConverter converter;

    @Test void openApiDocumentaAdministracionInterna() throws Exception {
        var spec = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(spec.path("paths").has("/internal/kafka/topics"));
        org.junit.jupiter.api.Assertions.assertTrue(spec.path("paths").has("/internal/kafka/groups"));
        org.junit.jupiter.api.Assertions.assertEquals("bearer", spec.path("components")
            .path("securitySchemes").path("bearerAuth").path("scheme").asText());
        org.junit.jupiter.api.Assertions.assertEquals("X-Internal-Key", spec.path("components")
            .path("securitySchemes").path("internalKey").path("name").asText());
        org.junit.jupiter.api.Assertions.assertTrue(spec.path("security").get(0).has("internalKey"));
        org.junit.jupiter.api.Assertions.assertTrue(spec.path("paths").path("/internal/kafka/topics")
            .path("post").path("responses").has("409"));
    }

    @Test void convierteClaimRolesDelAccessToken() {
        Jwt token = Jwt.withTokenValue("test").header("alg", "none")
            .claim("sub", "admin").claim("roles", java.util.List.of("Admin")).build();
        org.junit.jupiter.api.Assertions.assertTrue(converter.convert(token).getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("APPROLE_Admin")));
    }

    @Test void requiereClaveInternaYRolAdmin() throws Exception {
        mvc.perform(get("/internal/kafka/topics"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/internal/kafka/topics").header("X-Internal-Key", "test-internal-key"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/kafka/topics").header("X-Internal-Key", "test-internal-key")
            .with(jwt().authorities(new SimpleGrantedAuthority("APPROLE_Cliente"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/internal/kafka/topics").header("X-Internal-Key", "test-internal-key")
            .with(jwt().authorities(new SimpleGrantedAuthority("APPROLE_Admin"))))
            .andExpect(status().isOk());
        verify(management).topics();
    }

    @Test void validaCuerpoAntesDeCrearTopico() throws Exception {
        mvc.perform(post("/internal/kafka/topics").header("X-Internal-Key", "test-internal-key")
            .with(jwt().authorities(new SimpleGrantedAuthority("APPROLE_Admin")))
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"orders.events\"}"))
            .andExpect(status().isBadRequest());
        verify(management, never()).create(anyString(), anyInt(), anyInt(), anyLong(), anyString());
    }

    @Test void clienteNoPuedeModificarTopicos() throws Exception {
        mvc.perform(patch("/internal/kafka/topics/orders.events").header("X-Internal-Key", "test-internal-key")
            .with(jwt().authorities(new SimpleGrantedAuthority("APPROLE_Cliente")))
            .contentType(MediaType.APPLICATION_JSON).content("{\"retentionMs\":86400000}"))
            .andExpect(status().isForbidden());
        verify(management, never()).update(anyString(), any(), any(), any());
    }
}
