package com.pedidos360.audit.login;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity
public class EntraSignInCursor {
    @Id private String id;
    private Instant lastCompletedAt;
    protected EntraSignInCursor() {}
    public EntraSignInCursor(String id, Instant lastCompletedAt) {
        this.id = id; this.lastCompletedAt = lastCompletedAt;
    }
    public Instant getLastCompletedAt() { return lastCompletedAt; }
    public void setLastCompletedAt(Instant value) { this.lastCompletedAt = value; }
}
