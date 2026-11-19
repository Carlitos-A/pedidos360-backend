package com.pedidos360.pedidos.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_outbox", indexes = @Index(name = "idx_order_outbox_pending", columnList = "published_at,created_at"))
public class OrderOutboxMessage {
    @Id
    private String id;
    @Column(name = "event_id", nullable = false)
    private String eventId;
    @Column(nullable = false)
    private String exchangeName;
    @Column(nullable = false)
    private String routingKey;
    @Lob
    @Column(nullable = false)
    private String payload;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "published_at")
    private Instant publishedAt;
    private int attempts;
    @Column(length = 500)
    private String lastError;

    protected OrderOutboxMessage() {}
    public OrderOutboxMessage(String eventId, String exchangeName, String routingKey, String payload) {
        this.id = UUID.randomUUID().toString(); this.eventId = eventId;
        this.exchangeName = exchangeName; this.routingKey = routingKey; this.payload = payload;
        this.createdAt = Instant.now();
    }
    public String getId() { return id; }
    public String getEventId() { return eventId; }
    public String getExchangeName() { return exchangeName; }
    public String getRoutingKey() { return routingKey; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public void published() { this.publishedAt = Instant.now(); this.lastError = null; }
    public void failed(String message) {
        this.attempts++;
        this.lastError = message == null ? null : message.substring(0, Math.min(message.length(), 500));
    }
}
