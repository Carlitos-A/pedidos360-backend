package com.pedidos360.pedidos.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "order_notification", indexes = {
    @Index(name = "idx_notification_owner", columnList = "customer_id,customer_tenant_id,occurred_at"),
    @Index(name = "idx_notification_order", columnList = "order_id")
})
public class OrderNotification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "event_id", nullable = false, unique = true, length = 255) private String eventId;
    @Column(name = "order_id", nullable = false) private Long orderId;
    @Column(name = "customer_id", nullable = false, length = 255) private String customerId;
    @Column(name = "customer_tenant_id", nullable = false, length = 255) private String customerTenantId;
    @Column(nullable = false, length = 255) private String message;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;

    protected OrderNotification() {}
    public OrderNotification(String eventId, Long orderId, String customerId, String customerTenantId,
                             String message, Instant occurredAt) {
        this.eventId = eventId; this.orderId = orderId; this.customerId = customerId;
        this.customerTenantId = customerTenantId; this.message = message; this.occurredAt = occurredAt;
    }
    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public Long getOrderId() { return orderId; }
    public String getCustomerId() { return customerId; }
    public String getCustomerTenantId() { return customerTenantId; }
    public String getMessage() { return message; }
    public Instant getOccurredAt() { return occurredAt; }
}
