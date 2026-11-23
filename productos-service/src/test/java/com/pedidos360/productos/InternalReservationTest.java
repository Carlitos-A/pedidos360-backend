package com.pedidos360.productos;

import java.math.BigDecimal;
import com.pedidos360.productos.controller.InternalReservationController;
import com.pedidos360.productos.model.Producto;
import com.pedidos360.productos.repository.ProductoRepository;
import jakarta.transaction.Transactional;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.internal.key=test-key", "spring.rabbitmq.listener.simple.auto-startup=false"})
@AutoConfigureMockMvc
class InternalReservationTest {
    @Autowired InternalReservationController controller;
    @Autowired ProductoRepository products;
    @Autowired MockMvc mvc;

    @Test void healthInternoVerificaClaveYBaseDeProductos() throws Exception {
        mvc.perform(get("/internal/health")).andExpect(status().isForbidden());
        mvc.perform(get("/internal/health").header("X-Internal-Key", "test-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("products"))
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test void rutaInternaExigeClaveCompartida() throws Exception {
        String body = "{\"customerId\":\"owner\",\"customerTenantId\":\"tenant\",\"validatePrices\":true,\"items\":[]}";
        mvc.perform(put("/internal/reservations/http-test").contentType("application/json").content(body))
            .andExpect(status().isForbidden());
        mvc.perform(put("/internal/reservations/http-test").header("X-Internal-Key", "test-key")
            .contentType("application/json").content(body)).andExpect(status().isOk());
    }
    @Test void precioFraccionarioNoSeConvierteSilenciosamente() {
        var controller = new com.pedidos360.productos.controller.ProductApiController(products,
            org.mockito.Mockito.mock(com.pedidos360.productos.messaging.ProductAuditPublisher.class));
        var input = new com.pedidos360.productos.controller.ProductApiController.ProductInput(
            "A", "B", new BigDecimal("100.5"), 1, "owner", "tenant");
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> controller.create(input, null)).getStatusCode());
    }

    @Test @Transactional void reservaEsIdempotenteYAjustaDeltas() {
        Producto product = new Producto();
        product.setNombre("Producto"); product.setCategoria("Prueba"); product.setPriceClp(100L);
        product.setStock(5); product.setCustomerId("owner"); product.setCustomerTenantId("tenant");
        products.saveAndFlush(product);
        var two = request(product.getId(), 2, 100L);
        assertEquals(200L, controller.reserve("order-123", "test-key", two).total());
        assertEquals(3, product.getStock());
        assertEquals(200L, controller.reserve("order-123", "test-key", two).total());
        assertEquals(3, product.getStock());
        assertEquals(400L, controller.reserve("order-123", "test-key", request(product.getId(), 4, 100L)).total());
        assertEquals(1, product.getStock());
        controller.reserve("order-123", "test-key", new InternalReservationController.ReserveInput("owner", "tenant", false, List.of()));
        assertEquals(5, product.getStock());
    }

    @Test @Transactional void precioOPropietarioIncorrectoNoDescuentan() {
        Producto product = new Producto();
        product.setNombre("Producto"); product.setCategoria("Prueba"); product.setPriceClp(100L);
        product.setStock(5); product.setCustomerId("owner"); product.setCustomerTenantId("tenant");
        products.saveAndFlush(product);
        var error = assertThrows(ResponseStatusException.class,
            () -> controller.reserve("order-456", "test-key", request(product.getId(), 2, 99L)));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(5, product.getStock());
        var wrongOwner = new InternalReservationController.ReserveInput("other", "tenant", true,
            List.of(new InternalReservationController.Line(product.getId(), 1, 100L)));
        assertThrows(ResponseStatusException.class,
            () -> controller.reserve("order-789", "test-key", wrongOwner));
        assertEquals(5, product.getStock());
    }

    private InternalReservationController.ReserveInput request(Long id, int quantity, Long price) {
        return new InternalReservationController.ReserveInput("owner", "tenant", true,
            List.of(new InternalReservationController.Line(id, quantity, price)));
    }
}
