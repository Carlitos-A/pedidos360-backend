package com.pedidos360.pedidos.identity;

import com.pedidos360.pedidos.model.RegisteredCustomer;
import com.pedidos360.pedidos.repository.RegisteredCustomerRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CustomerDirectory {
    private final RegisteredCustomerRepository customers;
    private final String tenantId;
    public record Customer(String id, String tenantId, String name, String email) {}

    public CustomerDirectory(RegisteredCustomerRepository customers,
            @Value("${app.identity.tenant-id}") String tenantId) {
        this.customers = customers;
        this.tenantId = tenantId;
    }

    @Transactional
    public Customer register(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains("Cliente") || roles.contains("Admin"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Se requiere el rol Cliente");
        String id = jwt.getClaimAsString("oid"), tenant = jwt.getClaimAsString("tid");
        if (!validUuid(id) || !tenantId.equalsIgnoreCase(tenant))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Identidad de Cliente inválida");
        String name = bounded(jwt.getClaimAsString("name"));
        String email = bounded(first(jwt, "preferred_username", "upn", "email"));
        if (name.isBlank()) name = email.isBlank() ? id : email;
        RegisteredCustomer saved = customers.findById(new RegisteredCustomer.Key(id, tenantId))
            .orElseGet(() -> new RegisteredCustomer(id, tenantId));
        saved.setName(name);
        saved.setEmail(email);
        saved.setLastSeenAt(OffsetDateTime.now(ZoneOffset.UTC));
        customers.save(saved);
        return view(saved);
    }

    @Transactional(readOnly = true)
    public Customer resolve(String id, String tenant) {
        if (!validUuid(id) || !tenantId.equalsIgnoreCase(tenant))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Destinatario fuera del tenant configurado");
        return customers.findById(new RegisteredCustomer.Key(id, tenantId)).map(this::view)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Cliente no registrado; debe iniciar sesión en Pedidos360 primero"));
    }

    @Transactional(readOnly = true)
    public List<Customer> search(String query) {
        if (query == null || query.strip().length() < 2 || query.length() > 80)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe al menos dos caracteres");
        return customers.search(tenantId, query.strip(), PageRequest.of(0, 20))
            .stream().map(this::view).toList();
    }

    private Customer view(RegisteredCustomer value) {
        return new Customer(value.getCustomerId(), value.getCustomerTenantId(), value.getName(), value.getEmail());
    }
    private String first(Jwt jwt, String... keys) {
        for (String key : keys) {
            String value = jwt.getClaimAsString(key);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }
    private String bounded(String value) {
        if (value == null) return "";
        String stripped = value.strip();
        return stripped.substring(0, Math.min(255, stripped.length()));
    }
    private boolean validUuid(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException e) { return false; }
    }
}
