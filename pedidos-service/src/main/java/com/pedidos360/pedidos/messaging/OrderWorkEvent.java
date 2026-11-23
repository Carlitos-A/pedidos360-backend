package com.pedidos360.pedidos.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;

/** Validated snapshot emitted by the order service after inventory is confirmed. */
public record OrderWorkEvent(String eventId, long orderId, String customerName, String customerId,
                             String customerTenantId, long total, Instant createdAt, Instant occurredAt,
                             List<OrderEventV2.Line> items) {
    public static OrderWorkEvent parse(ObjectMapper mapper, byte[] body) {
        try {
            OrderEventV2 event = mapper.readValue(body, OrderEventV2.class);
            if (event.schemaVersion() != 2 || !"ORDER_CREATED".equals(event.eventType())
                || invalid(event.eventId(), 255) || event.orderId() == null || event.orderId() < 1
                || invalid(event.customerName(), 255) || invalid(event.customerId(), 255)
                || invalid(event.customerTenantId(), 255) || event.total() == null || event.total() < 0
                || event.createdAt() == null || event.occurredAt() == null
                || event.items() == null || event.items().isEmpty() || event.items().size() > 100)
                throw new InvalidWorkEventException();
            long calculated = 0;
            for (OrderEventV2.Line line : event.items()) {
                if (line == null || invalid(line.productId(), 255) || invalid(line.name(), 255)
                    || line.quantity() == null || line.quantity() < 1
                    || line.unitPrice() == null || line.unitPrice() < 0)
                    throw new InvalidWorkEventException();
                calculated = Math.addExact(calculated, Math.multiplyExact(line.quantity(), line.unitPrice()));
            }
            if (calculated != event.total()) throw new InvalidWorkEventException();
            return new OrderWorkEvent(event.eventId(), event.orderId(), event.customerName(),
                event.customerId(), event.customerTenantId(), event.total(), event.createdAt(),
                event.occurredAt(), List.copyOf(event.items()));
        } catch (Exception failure) {
            if (failure instanceof InvalidWorkEventException invalid) throw invalid;
            throw new InvalidWorkEventException();
        }
    }
    private static boolean invalid(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }
    public static class InvalidWorkEventException extends RuntimeException {
        public InvalidWorkEventException() { super("Evento de creación sin snapshot verificable"); }
    }
}
