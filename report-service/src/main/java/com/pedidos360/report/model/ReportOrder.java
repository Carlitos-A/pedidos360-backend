package com.pedidos360.report.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "report_order", indexes = {
    @Index(name = "idx_report_created", columnList = "created_at"),
    @Index(name = "idx_report_completed", columnList = "completed_at")
})
public class ReportOrder {
    @Id private Long id;
    @Column(nullable = false) private long revision;
    @Column(nullable = false, length = 32) private String status;
    @Column(nullable = false) private long total;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "items_json", nullable = false, columnDefinition = "LONGTEXT") private String itemsJson;
    protected ReportOrder() {}
    public ReportOrder(Long id, long revision, String status, long total, Instant createdAt,
                       Instant completedAt, String itemsJson) {
        this.id = id; this.revision = revision; this.status = status; this.total = total;
        this.createdAt = createdAt; this.completedAt = completedAt; this.itemsJson = itemsJson;
    }
    public Long getId() { return id; }
    public long getRevision() { return revision; }
    public String getStatus() { return status; }
    public long getTotal() { return total; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getItemsJson() { return itemsJson; }
}
