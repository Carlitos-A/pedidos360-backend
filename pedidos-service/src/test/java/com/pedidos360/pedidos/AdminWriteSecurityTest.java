package com.pedidos360.pedidos;

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

@SpringBootTest(properties = {"app.security.require-write-scope=true",
    "app.cors.allowed-origins=https://carlitos-a.github.io", "spring.rabbitmq.listener.simple.auto-startup=false"})
@AutoConfigureMockMvc
class AdminWriteSecurityTest {
    @Autowired MockMvc mvc;

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

    @Test void preflightPermitePatchYHeadersDeLaSpa() throws Exception {
        mvc.perform(options("/api/orders/1/status")
            .header("Origin", "https://carlitos-a.github.io")
            .header("Access-Control-Request-Method", "PATCH")
            .header("Access-Control-Request-Headers", "authorization,content-type"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://carlitos-a.github.io"))
            .andExpect(header().string("Access-Control-Allow-Methods", containsString("PATCH")));
    }
}
