package com.pedidos360.productos;

import org.junit.jupiter.api.Test;
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
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.security.require-write-scope=true",
    "app.cors.allowed-origins=https://carlitos-a.github.io"})
@AutoConfigureMockMvc
class AdminWriteSecurityTest {
    @Autowired MockMvc mvc;

    @Test void livenessPermiteHealthCheckSinTokenNiClave() throws Exception {
        mvc.perform(get("/internal/live"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("UP"));
    }

    @Test void produccionExigeScopeDeEscrituraPorDefecto() throws Exception {
        var source = new org.springframework.boot.env.YamlPropertySourceLoader()
            .load("prod", new org.springframework.core.io.ClassPathResource("application-prod.yml")).getFirst();
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(source);
        assertEquals("true", env.getProperty("app.security.require-write-scope"));
    }

    @Test void openApiPublicaContratoYSeguridad() throws Exception {
        var spec = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertTrue(spec.path("paths").has("/api/products"));
        assertTrue(spec.path("paths").has("/api/products/{id}/stock"));
        assertFalse(spec.path("paths").has("/productos"));
        assertEquals("bearer", spec.path("components").path("securitySchemes")
            .path("bearerAuth").path("scheme").asText());
        assertTrue(spec.path("paths").path("/api/products").path("get").path("responses").has("401"));
        assertTrue(spec.path("paths").path("/api/products/{id}/stock").path("patch")
            .path("responses").has("409"));
        assertTrue(spec.path("components").path("schemas").has("ProductInput"));
    }

    @Test void escrituraExigeRolYScope() throws Exception {
        var admin = new SimpleGrantedAuthority("APPROLE_Admin");
        var cliente = new SimpleGrantedAuthority("APPROLE_Cliente");
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("{}")
            .with(jwt().jwt(token -> token.claim("scp", "pedidos.read")).authorities(admin)))
            .andExpect(status().isForbidden());
        mvc.perform(patch("/api/products/1/stock").contentType(MediaType.APPLICATION_JSON).content("{}")
            .with(jwt().jwt(token -> token.claim("scp", "pedidos.read")).authorities(admin)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("{}")
            .with(jwt().jwt(token -> token.claim("scp", "pedidos.write")).authorities(cliente)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("{}")
            .with(jwt().jwt(token -> token.claim("scp", "pedidos.write")).authorities(admin)))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/products")
            .with(jwt().jwt(token -> token.claim("scp", "pedidos.read")
                .claim("oid", "owner").claim("tid", "tenant")).authorities(cliente)))
            .andExpect(status().isOk());
    }

    @Test void preflightPermitePatchYHeadersDeLaSpa() throws Exception {
        mvc.perform(options("/api/products/1/stock")
            .header("Origin", "https://carlitos-a.github.io")
            .header("Access-Control-Request-Method", "PATCH")
            .header("Access-Control-Request-Headers", "authorization,content-type"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://carlitos-a.github.io"))
            .andExpect(header().string("Access-Control-Allow-Methods", containsString("PATCH")));
    }
}
