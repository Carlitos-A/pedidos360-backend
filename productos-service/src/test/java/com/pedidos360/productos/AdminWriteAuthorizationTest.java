package com.pedidos360.productos;

import com.pedidos360.productos.security.AdminWriteAuthorization;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;

class AdminWriteAuthorizationTest {
    @Test void exigeAdminYScopeAlActivarse() {
        assertTrue(allowed(true, true, "pedidos.read pedidos.write"));
        assertFalse(allowed(true, true, "pedidos.read"));
        assertFalse(allowed(true, false, "pedidos.write"));
        assertTrue(allowed(false, true, "pedidos.read"));
    }

    private boolean allowed(boolean requireScope, boolean admin, String scopes) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("scp", scopes).build();
        var authorities = admin ? List.of(new SimpleGrantedAuthority("APPROLE_Admin"))
            : List.of(new SimpleGrantedAuthority("APPROLE_Cliente"));
        var token = new JwtAuthenticationToken(jwt, authorities);
        return AdminWriteAuthorization.require(requireScope).authorize(() -> token, null).isGranted();
    }
}
