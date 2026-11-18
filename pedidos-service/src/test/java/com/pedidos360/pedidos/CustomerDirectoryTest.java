package com.pedidos360.pedidos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.pedidos.identity.CustomerDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class CustomerDirectoryTest {
    private final CustomerDirectory directory = new CustomerDirectory(new ObjectMapper(),
        "bb5324af-c266-41ed-b36c-a971641c7af2", "", "", "", "");

    @Test void rechazaTenantAjenoAntesDeConsultarGraph() {
        var error = assertThrows(ResponseStatusException.class, () -> directory.resolve(
            "11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test void sinCredencialesFallaCerrado() {
        var error = assertThrows(ResponseStatusException.class, () -> directory.resolve(
            "11111111-1111-1111-1111-111111111111", "bb5324af-c266-41ed-b36c-a971641c7af2"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }
}
