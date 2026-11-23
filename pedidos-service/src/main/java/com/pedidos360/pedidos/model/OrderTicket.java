package com.pedidos360.pedidos.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "order_ticket", indexes = @Index(name = "idx_ticket_customer", columnList = "customer_id,customer_tenant_id"))
public class OrderTicket {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "event_id", nullable = false, unique = true, length = 255) private String eventId;
    @Column(name = "order_id", nullable = false, unique = true) private Long orderId;
    @Column(name = "ticket_number", nullable = false, unique = true, length = 64) private String ticketNumber;
    @Column(name = "customer_name", nullable = false, length = 255) private String customerName;
    @Column(name = "customer_id", nullable = false, length = 255) private String customerId;
    @Column(name = "customer_tenant_id", nullable = false, length = 255) private String customerTenantId;
    @Column(name = "total_clp", nullable = false) private Long totalClp;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "issued_at", nullable = false) private Instant issuedAt;
    @Column(name = "items_json", nullable = false, columnDefinition = "TEXT") private String itemsJson;

    protected OrderTicket() {}
    public OrderTicket(String eventId, Long orderId, String ticketNumber, String customerName,
                       String customerId, String customerTenantId, Long totalClp, Instant createdAt,
                       Instant issuedAt, String itemsJson) {
        this.eventId = eventId; this.orderId = orderId; this.ticketNumber = ticketNumber;
        this.customerName = customerName; this.customerId = customerId;
        this.customerTenantId = customerTenantId; this.totalClp = totalClp;
        this.createdAt = createdAt; this.issuedAt = issuedAt; this.itemsJson = itemsJson;
    }
    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public Long getOrderId() { return orderId; }
    public String getTicketNumber() { return ticketNumber; }
    public String getCustomerName() { return customerName; }
    public String getCustomerId() { return customerId; }
    public String getCustomerTenantId() { return customerTenantId; }
    public Long getTotalClp() { return totalClp; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getIssuedAt() { return issuedAt; }
    public String getItemsJson() { return itemsJson; }
}
