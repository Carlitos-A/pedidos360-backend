package com.pedidos360.productos.messaging;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import java.time.OffsetDateTime;

@Entity
public class ProductAuditOutbox {
    @Id private String eventId;
    private String productId;
    private String actorEmail;
    private String actorId;
    private String actorTenantId;
    @Column(length = 32)
    private String destination;
    private OffsetDateTime occurredAt;
    public ProductAuditOutbox() {}
    public ProductAuditOutbox(String eventId, String productId, String actorEmail, OffsetDateTime occurredAt) {
        this(eventId, productId, actorEmail, null, null, occurredAt);
    }
    public ProductAuditOutbox(String eventId, String productId, String actorEmail,
                              String actorId, String actorTenantId, OffsetDateTime occurredAt) {
        this(eventId, productId, actorEmail, actorId, actorTenantId, occurredAt, "rabbit");
    }
    public ProductAuditOutbox(String eventId, String productId, String actorEmail,
                              String actorId, String actorTenantId, OffsetDateTime occurredAt, String destination) {
        this.eventId = eventId; this.productId = productId; this.actorEmail = actorEmail; this.occurredAt = occurredAt;
        this.actorId = actorId; this.actorTenantId = actorTenantId; this.destination = destination;
    }
    public String getEventId() { return eventId; }
    public String getProductId() { return productId; }
    public String getActorEmail() { return actorEmail; }
    public String getActorId() { return actorId; }
    public String getActorTenantId() { return actorTenantId; }
    public String getDestination() { return destination == null ? "rabbit" : destination; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
