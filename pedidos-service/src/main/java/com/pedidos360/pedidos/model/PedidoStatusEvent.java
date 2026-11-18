package com.pedidos360.pedidos.model;

import jakarta.persistence.Embeddable;
import java.time.OffsetDateTime;

@Embeddable
public class PedidoStatusEvent {
    private String status;
    private OffsetDateTime changedAt;
    private String actorName;
    private String actorRole;
    public PedidoStatusEvent() {}
    public PedidoStatusEvent(String status, OffsetDateTime changedAt, String actorName, String actorRole) {
        this.status = status; this.changedAt = changedAt; this.actorName = actorName; this.actorRole = actorRole;
    }
    public String getStatus() { return status; }
    public OffsetDateTime getChangedAt() { return changedAt; }
    public String getActorName() { return actorName; }
    public String getActorRole() { return actorRole; }
}
