package com.pedidos360.report.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "report_receipt", indexes = @jakarta.persistence.Index(name = "idx_report_processed", columnList = "processed_at"))
public class ReportReceipt {
    @Id private String eventId;
    @jakarta.persistence.Column(name = "processed_at")
    private Instant processedAt;
    protected ReportReceipt() {}
    public ReportReceipt(String eventId) { this.eventId = eventId; this.processedAt = Instant.now(); }
    public Instant getProcessedAt() { return processedAt; }
}
