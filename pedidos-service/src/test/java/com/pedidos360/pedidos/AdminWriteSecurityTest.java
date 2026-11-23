package com.pedidos360.pedidos;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(properties = {"app.security.require-write-scope=true",
    "app.cors.allowed-origins=https://carlitos-a.github.io", "spring.rabbitmq.listener.simple.auto-startup=false"})
@AutoConfigureMockMvc
class AdminWriteSecurityTest {
    @Autowired MockMvc mvc;

    @Test void livenessPermiteHealthCheckSinTokenNiClave() throws Exception {
        mvc.perform(get("/internal/live"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("UP"));
    }

    @Test void healthAgregadoSoloEsVisibleParaAdmin() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health").with(jwt().authorities(new SimpleGrantedAuthority("APPROLE_Cliente"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/health").with(jwt().authorities(new SimpleGrantedAuthority("APPROLE_Admin"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.services[0].name").value("orders"));
    }

    @Test void produccionExigeScopeYSeleccionaTransporteKafka() throws Exception {
        var loader = new org.springframework.boot.env.YamlPropertySourceLoader();
        var prod = loader.load("prod", new org.springframework.core.io.ClassPathResource("application-prod.yml")).getFirst();
        var compose = loader.load("compose", new org.springframework.core.io.ClassPathResource("application-kafka-compose.yml")).getFirst();
        var msk = loader.load("msk", new org.springframework.core.io.ClassPathResource("application-msk.yml")).getFirst();
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(prod);
        assertEquals("true", env.getProperty("app.security.require-write-scope"));
        env.getPropertySources().addFirst(compose);
        assertEquals("PLAINTEXT", env.getProperty("spring.kafka.properties.security.protocol"));
        assertEquals("localhost:9092,localhost:9093,localhost:9094",
            env.getProperty("spring.kafka.bootstrap-servers"));
        env.getPropertySources().addFirst(msk);
        assertEquals("SASL_SSL", env.getProperty("spring.kafka.properties.security.protocol"));
    }

    @Test void escrituraExigeRolYScope() throws Exception {
        var admin = new SimpleGrantedAuthority("APPROLE_Admin");
        var cliente = new SimpleGrantedAuthority("APPROLE_Cliente");
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{}")
            .with(jwt().jwt(token -> token.claim("scp", "pedidos.read")).authorities(admin)))
            .andExpect(status().isForbidden());
        mvc.perform(patch("/api/orders/1/status").contentType(MediaType.APPLICATION_JSON).content("{}")
            .with(jwt().jwt(token -> token.claim("scp", "pedidos.read")).authorities(admin)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{}")
            .with(jwt().jwt(token -> token.claim("scp", "pedidos.write")).authorities(cliente)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{}")
            .with(jwt().jwt(token -> token.claim("scp", "pedidos.write")).authorities(admin)))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders")
            .with(jwt().jwt(token -> token.claim("scp", "pedidos.read")
                .claim("oid", "owner").claim("tid", "tenant")).authorities(cliente)))
            .andExpect(status().isOk());
    }

    @Test void openApiDocumentaContratoYBearer() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        var spec = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        assertTrue(spec.path("paths").has("/api/orders"));
        assertTrue(spec.path("paths").has("/api/orders/{id}/status"));
        assertTrue(spec.path("paths").has("/api/notifications"));
        assertFalse(spec.path("paths").has("/pedidos"));
        assertEquals("bearer", spec.path("components").path("securitySchemes")
            .path("bearerAuth").path("scheme").asText());
        var list = spec.path("paths").path("/api/orders").path("get");
        assertTrue(list.path("responses").has("401"));
        assertTrue(list.path("responses").has("403"));
        assertTrue(spec.path("paths").path("/api/orders/{id}/status")
            .path("patch").path("responses").has("409"));
        assertTrue(spec.path("components").path("schemas").has("OrderInput"));
    }

    @Test void preflightPermitePatchYHeadersDeLaSpa() throws Exception {
        mvc.perform(options("/api/orders/1/status")
            .header("Origin", "https://carlitos-a.github.io")
            .header("Access-Control-Request-Method", "PATCH")
            .header("Access-Control-Request-Headers", "authorization,content-type"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://carlitos-a.github.io"))
            .andExpect(header().string("Access-Control-Allow-Methods", containsString("PATCH")));
    }

    @Test void registroDlqExigeAdmin() throws Exception {
        mvc.perform(get("/internal/rejections/rabbit")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/rejections/rabbit")
            .with(jwt().authorities(new SimpleGrantedAuthority("APPROLE_Cliente"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/internal/rejections/rabbit")
            .with(jwt().authorities(new SimpleGrantedAuthority("APPROLE_Admin"))))
            .andExpect(status().isOk());
    }
}
