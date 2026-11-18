package com.pedidos360.pedidos.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(indexes = @Index(name = "idx_audit_occurred", columnList = "occurredAt"))
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true, nullable = false)
    private String eventKey;
    @Column(nullable = false)
    private String type;
    @Column(nullable = false)
    private String description;
    private String actorEmail;
    @Column(nullable = false)
    private OffsetDateTime occurredAt;
    public AuditEvent() {}
    public AuditEvent(String eventKey, String type, String description, String actorEmail, OffsetDateTime occurredAt) {
        this.eventKey = eventKey; this.type = type; this.description = description;
        this.actorEmail = actorEmail; this.occurredAt = occurredAt;
    }
    public Long getId() { return id; }
    public String getEventKey() { return eventKey; }
    public String getType() { return type; }
    public String getDescription() { return description; }
    public String getActorEmail() { return actorEmail; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
