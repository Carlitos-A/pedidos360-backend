package com.pedidos360.pedidos;

import com.pedidos360.pedidos.model.OrderStatus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderStatusTest {
    @Test void transicionesPermitidasYTerminales() {
        assertTrue(OrderStatus.CREATED.canTransitionTo(OrderStatus.ACCEPTED));
        assertTrue(OrderStatus.CREATED.canTransitionTo(OrderStatus.CANCELLED));
        assertTrue(OrderStatus.ACCEPTED.canTransitionTo(OrderStatus.IN_PROGRESS));
        assertTrue(OrderStatus.ACCEPTED.canTransitionTo(OrderStatus.CANCELLED));
        assertTrue(OrderStatus.IN_PROGRESS.canTransitionTo(OrderStatus.COMPLETED));
        assertTrue(OrderStatus.IN_PROGRESS.canTransitionTo(OrderStatus.CANCELLED));
        for (OrderStatus state : OrderStatus.values()) {
            assertFalse(OrderStatus.COMPLETED.canTransitionTo(state));
            assertFalse(OrderStatus.CANCELLED.canTransitionTo(state));
        }
        assertFalse(OrderStatus.CREATED.canTransitionTo(OrderStatus.COMPLETED));
        assertFalse(OrderStatus.ACCEPTED.canTransitionTo(OrderStatus.CREATED));
    }

    @Test void traduceSoloLosTresEstadosHistoricosConocidos() {
        assertEquals(OrderStatus.CREATED, OrderStatus.fromStored("PENDIENTE").orElseThrow());
        assertEquals(OrderStatus.IN_PROGRESS, OrderStatus.fromStored("EN_PREPARACION").orElseThrow());
        assertEquals(OrderStatus.COMPLETED, OrderStatus.fromStored("ENTREGADO").orElseThrow());
        assertTrue(OrderStatus.fromStored("DESPACHADO").isEmpty());
        assertTrue(OrderStatus.fromInput("PENDIENTE").isEmpty());
        assertEquals(OrderStatus.CANCELLED, OrderStatus.fromInput("CANCELLED").orElseThrow());
    }
}
