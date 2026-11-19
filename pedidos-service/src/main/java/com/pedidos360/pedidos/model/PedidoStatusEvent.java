package com.pedidos360.pedidos.model;

import jakarta.persistence.Embeddable;
import java.time.OffsetDateTime;

@Embeddable
public class PedidoStatusEvent {
    private String status;
    private String previousStatus;
    private OffsetDateTime changedAt;
    private String actorName;
    private String actorRole;
    private String actorId;
    private String actorTenantId;
    public PedidoStatusEvent() {}
    public PedidoStatusEvent(String status, OffsetDateTime changedAt, String actorName, String actorRole) {
        this.status = status; this.changedAt = changedAt; this.actorName = actorName; this.actorRole = actorRole;
    }
    public PedidoStatusEvent(String previousStatus, String status, OffsetDateTime changedAt, OrderActor actor) {
        this.previousStatus = previousStatus; this.status = status; this.changedAt = changedAt;
        this.actorId = actor.id(); this.actorTenantId = actor.tenantId();
        this.actorName = actor.name(); this.actorRole = actor.role();
    }
    public String getStatus() { return status; }
    public String getPreviousStatus() { return previousStatus; }
    public OffsetDateTime getChangedAt() { return changedAt; }
    public String getActorName() { return actorName; }
    public String getActorRole() { return actorRole; }
    public String getActorId() { return actorId; }
    public String getActorTenantId() { return actorTenantId; }
}
