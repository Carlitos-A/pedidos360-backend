package com.pedidos360.productos.messaging;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.OffsetDateTime;

@Entity
public class ProductAuditOutbox {
    @Id private String eventId;
    private String productId;
    private String actorEmail;
    private OffsetDateTime occurredAt;
    public ProductAuditOutbox() {}
    public ProductAuditOutbox(String eventId, String productId, String actorEmail, OffsetDateTime occurredAt) {
        this.eventId = eventId; this.productId = productId; this.actorEmail = actorEmail; this.occurredAt = occurredAt;
    }
    public String getEventId() { return eventId; }
    public String getProductId() { return productId; }
    public String getActorEmail() { return actorEmail; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
