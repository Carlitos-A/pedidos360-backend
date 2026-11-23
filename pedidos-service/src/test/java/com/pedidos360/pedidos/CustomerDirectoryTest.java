package com.pedidos360.pedidos;

import com.pedidos360.pedidos.identity.CustomerDirectory;
import com.pedidos360.pedidos.model.RegisteredCustomer;
import com.pedidos360.pedidos.repository.RegisteredCustomerRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CustomerDirectoryTest {
    private static final String TENANT = "bb5324af-c266-41ed-b36c-a971641c7af2";
    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private final RegisteredCustomerRepository customers = mock(RegisteredCustomerRepository.class);
    private final CustomerDirectory directory = new CustomerDirectory(customers, TENANT);

    @Test void registraSoloLaIdentidadClienteDelJwtYLaResuelve() {
        when(customers.findById(any())).thenReturn(Optional.empty());
        var customer = directory.register(token(List.of("Cliente"), TENANT));
        assertEquals(ID, customer.id());
        assertEquals(TENANT, customer.tenantId());
        assertEquals("María", customer.name());
        verify(customers).save(any(RegisteredCustomer.class));
    }

    @Test void noRegistraAdminAunqueTengaAmbosRoles() {
        var error = assertThrows(ResponseStatusException.class,
            () -> directory.register(token(List.of("Cliente", "Admin"), TENANT)));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(customers);
    }

    @Test void noAceptaTenantAjenoNiClienteNoRegistrado() {
        var foreign = assertThrows(ResponseStatusException.class,
            () -> directory.register(token(List.of("Cliente"), "22222222-2222-2222-2222-222222222222")));
        assertEquals(HttpStatus.FORBIDDEN, foreign.getStatusCode());
        var missing = assertThrows(ResponseStatusException.class, () -> directory.resolve(ID, TENANT));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
    }

    private Jwt token(List<String> roles, String tenant) {
        return new Jwt("token", java.time.Instant.now(), java.time.Instant.now().plusSeconds(60),
            Map.of("alg", "RS256"), Map.of("roles", roles, "oid", ID, "tid", tenant,
                "name", "María", "preferred_username", "maria@example.test"));
    }
}
