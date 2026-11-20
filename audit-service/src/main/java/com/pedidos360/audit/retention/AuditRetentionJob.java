package com.pedidos360.audit.retention;

import com.pedidos360.audit.repository.AuditEventRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AuditRetentionJob {
    private final AuditEventRepository repository;
    private final int retentionDays;
    public AuditRetentionJob(AuditEventRepository repository,
                             @Value("${app.audit.retention-days:0}") int retentionDays) {
        if (retentionDays < 0) throw new IllegalArgumentException("Retención inválida");
        this.repository = repository; this.retentionDays = retentionDays;
    }
    @Scheduled(cron = "${app.audit.retention-cron:0 30 3 * * *}", zone = "UTC")
    @Transactional
    public void purge() {
        // Zero preserves all events until an explicit retention policy is approved.
        if (retentionDays > 0) repository.deleteByOccurredAtBefore(OffsetDateTime.now(ZoneOffset.UTC).minusDays(retentionDays));
    }
}
