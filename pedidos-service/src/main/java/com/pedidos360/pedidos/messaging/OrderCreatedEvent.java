package com.pedidos360.pedidos.messaging;

import java.time.LocalDateTime;

/** Evento estable que se publica después de guardar un pedido. */
public record OrderCreatedEvent(
        Long orderId,
        String customer,
        String description,
        Double total,
        String status,
        LocalDateTime createdAt) {
}
