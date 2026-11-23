package com.pedidos360.audit.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "rejected_kafka_event", uniqueConstraints = @UniqueConstraint(
    name = "uq_rejected_kafka_position", columnNames = {"dlt_topic", "dlt_partition", "dlt_offset"}),
    indexes = @Index(name = "idx_rejected_kafka_observed", columnList = "observed_at,id"))
public class RejectedKafkaEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "dlt_topic", nullable = false, length = 255) private String dltTopic;
    @Column(name = "dlt_partition", nullable = false) private int dltPartition;
    @Column(name = "dlt_offset", nullable = false) private long dltOffset;
    @Column(name = "source_topic", length = 255) private String sourceTopic;
    @Column(name = "source_partition") private Integer sourcePartition;
    @Column(name = "source_offset") private Long sourceOffset;
    @Column(length = 500) private String cause;
    @Column(name = "rejected_at") private OffsetDateTime rejectedAt;
    @Column(name = "observed_at", nullable = false) private OffsetDateTime observedAt;
    @Column(name = "payload_sha256", nullable = false, length = 64) private String payloadSha256;

    protected RejectedKafkaEvent() {}
    public RejectedKafkaEvent(String dltTopic, int dltPartition, long dltOffset, String sourceTopic,
                              Integer sourcePartition, Long sourceOffset, String cause,
                              OffsetDateTime rejectedAt, OffsetDateTime observedAt, String payloadSha256) {
        this.dltTopic = dltTopic; this.dltPartition = dltPartition; this.dltOffset = dltOffset;
        this.sourceTopic = sourceTopic; this.sourcePartition = sourcePartition; this.sourceOffset = sourceOffset;
        this.cause = cause; this.rejectedAt = rejectedAt; this.observedAt = observedAt;
        this.payloadSha256 = payloadSha256;
    }
    public Long getId() { return id; }
    public String getDltTopic() { return dltTopic; }
    public int getDltPartition() { return dltPartition; }
    public long getDltOffset() { return dltOffset; }
    public String getSourceTopic() { return sourceTopic; }
    public Integer getSourcePartition() { return sourcePartition; }
    public Long getSourceOffset() { return sourceOffset; }
    public String getCause() { return cause; }
    public OffsetDateTime getRejectedAt() { return rejectedAt; }
    public OffsetDateTime getObservedAt() { return observedAt; }
    public String getPayloadSha256() { return payloadSha256; }
}
