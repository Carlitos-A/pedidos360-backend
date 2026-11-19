package com.pedidos360.pedidos.model;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

public record OrderActor(String id, String tenantId, String name, String role, String email) {
    public static OrderActor fromJwt(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("Admin"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String id = jwt.getClaimAsString("oid"), tenantId = jwt.getClaimAsString("tid");
        if (id == null || id.isBlank() || tenantId == null || tenantId.isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Token sin identidad de actor");
        String name = jwt.getClaimAsString("name");
        if (name == null || name.isBlank()) name = jwt.getSubject();
        String email = jwt.getClaimAsString("preferred_username");
        if (email == null) email = jwt.getClaimAsString("email");
        return new OrderActor(id, tenantId, name, "Admin", email);
    }
}
