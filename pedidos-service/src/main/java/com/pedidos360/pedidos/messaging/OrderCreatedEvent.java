package com.pedidos360.pedidos.messaging;

import java.time.LocalDateTime;

/** Esquema v1 conservado como referencia para mensajes históricos en RabbitMQ/DLQ. */
@Deprecated
public record OrderCreatedEvent(
        Long orderId,
        String customer,
        String description,
        Double total,
        String status,
        LocalDateTime createdAt) {
}
