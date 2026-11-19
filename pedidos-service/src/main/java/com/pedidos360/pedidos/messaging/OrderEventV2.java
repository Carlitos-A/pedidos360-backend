package com.pedidos360.pedidos.messaging;

import com.pedidos360.pedidos.model.Pedido;
import com.pedidos360.pedidos.model.PedidoItem;
import com.pedidos360.pedidos.model.PedidoStatusEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderEventV2(int schemaVersion, String eventId, String eventType, String auditKey,
                           Long orderId, String customerName, String customerId, String customerTenantId,
                           String detail, Long total, String previousStatus, String status,
                           Instant createdAt, Instant occurredAt, String actorId, String actorTenantId,
                           String actorName, String actorRole, String actorEmail, List<Line> items) {
    public record Line(String productId, String name, Integer quantity, Long unitPrice) {}

    public static OrderEventV2 from(Pedido order, PedidoStatusEvent change, String type,
                                    String auditKey, String actorEmail) {
        return new OrderEventV2(2, UUID.randomUUID().toString(), type, auditKey,
            order.getId(), order.getCliente(), order.getCustomerId(), order.getCustomerTenantId(),
            order.getDetalle(), order.getTotalClp(), change.getPreviousStatus(), change.getStatus(),
            order.getCreatedAtUtc(), change.getChangedAt().toInstant(), change.getActorId(),
            change.getActorTenantId(), change.getActorName(), change.getActorRole(), actorEmail,
            order.getItems().stream().map(OrderEventV2::line).toList());
    }

    private static Line line(PedidoItem item) {
        return new Line(item.getProductId(), item.getName(), item.getQuantity(), item.getUnitPrice());
    }
}
