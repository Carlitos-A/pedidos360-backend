package com.pedidos360.productos;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProductAccessTest {
    @Autowired MockMvc mvc;

    @Test void clienteSoloPuedeLeerApiFiltrada() throws Exception {
        var cliente = new SimpleGrantedAuthority("APPROLE_Cliente");
        mvc.perform(get("/api/products").with(jwt().jwt(token -> token.claim("roles", java.util.List.of("Cliente"))
            .claim("oid", "customer-1").claim("tid", "tenant-1")).authorities(cliente))).andExpect(status().isOk());
        mvc.perform(get("/productos").with(jwt().authorities(cliente))).andExpect(status().isForbidden());
    }

    @Test void adminPuedeLeerCatalogo() throws Exception {
        mvc.perform(get("/api/products").with(jwt().jwt(token -> token.claim("roles", java.util.List.of("Admin")))
            .authorities(new SimpleGrantedAuthority("APPROLE_Admin"))))
            .andExpect(status().isOk());
    }
}
