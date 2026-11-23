package com.pedidos360.pedidos.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "rejected_rabbit_message", indexes = @Index(name = "idx_rejected_rabbit_observed", columnList = "observed_at,id"))
public class RejectedRabbitMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "fingerprint", nullable = false, unique = true, length = 64) private String fingerprint;
    @Column(name = "dlq_name", nullable = false, length = 255) private String dlqName;
    @Column(name = "source_queue", length = 255) private String sourceQueue;
    @Column(name = "death_reason", length = 100) private String deathReason;
    @Column(name = "message_id", length = 255) private String messageId;
    @Column(name = "payload_sha256", nullable = false, length = 64) private String payloadSha256;
    @Column(name = "observed_at", nullable = false) private OffsetDateTime observedAt;

    protected RejectedRabbitMessage() {}
    public RejectedRabbitMessage(String fingerprint, String dlqName, String sourceQueue,
                                 String deathReason, String messageId, String payloadSha256,
                                 OffsetDateTime observedAt) {
        this.fingerprint = fingerprint; this.dlqName = dlqName; this.sourceQueue = sourceQueue;
        this.deathReason = deathReason; this.messageId = messageId;
        this.payloadSha256 = payloadSha256; this.observedAt = observedAt;
    }
    public Long getId() { return id; }
    public String getFingerprint() { return fingerprint; }
    public String getDlqName() { return dlqName; }
    public String getSourceQueue() { return sourceQueue; }
    public String getDeathReason() { return deathReason; }
    public String getMessageId() { return messageId; }
    public String getPayloadSha256() { return payloadSha256; }
    public OffsetDateTime getObservedAt() { return observedAt; }
}
