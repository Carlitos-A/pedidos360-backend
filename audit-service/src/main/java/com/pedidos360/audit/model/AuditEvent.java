package com.pedidos360.audit.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "audit_event", indexes = {
    @Index(name = "idx_audit_occurred_id", columnList = "occurred_at,id"),
    @Index(name = "idx_audit_actor_occurred", columnList = "actor_email,occurred_at"),
    @Index(name = "idx_audit_type_occurred", columnList = "type,occurred_at")
})
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id", nullable = false, unique = true, length = 255)
    private String eventId;
    @Column(nullable = false, length = 40)
    private String type;
    @Column(nullable = false, length = 255)
    private String description;
    @Column(name = "actor_email", length = 255)
    private String actorEmail;
    @Column(name = "actor_id", length = 255)
    private String actorId;
    @Column(name = "actor_tenant_id", length = 255)
    private String actorTenantId;
    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    protected AuditEvent() {}
    public AuditEvent(String eventId, String type, String description, String actorEmail,
                      String actorId, String actorTenantId, OffsetDateTime occurredAt) {
        this.eventId = eventId; this.type = type; this.description = description;
        this.actorEmail = actorEmail; this.actorId = actorId;
        this.actorTenantId = actorTenantId; this.occurredAt = occurredAt;
    }
    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getType() { return type; }
    public String getDescription() { return description; }
    public String getActorEmail() { return actorEmail; }
    public String getActorId() { return actorId; }
    public String getActorTenantId() { return actorTenantId; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
