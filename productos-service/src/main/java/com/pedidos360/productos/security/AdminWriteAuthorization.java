package com.pedidos360.productos.security;

import java.util.Arrays;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

public final class AdminWriteAuthorization {
    private AdminWriteAuthorization() {}

    public static AuthorizationManager<RequestAuthorizationContext> require(boolean requireWriteScope) {
        return (authentication, request) -> {
            var current = authentication.get();
            boolean admin = current.getAuthorities().stream()
                .anyMatch(authority -> "APPROLE_Admin".equals(authority.getAuthority()));
            if (!admin || !requireWriteScope) return new AuthorizationDecision(admin);
            if (!(current instanceof JwtAuthenticationToken jwt)) return new AuthorizationDecision(false);
            String scopes = jwt.getToken().getClaimAsString("scp");
            return new AuthorizationDecision(scopes != null && Arrays.asList(scopes.split(" ")).contains("pedidos.write"));
        };
    }
}
