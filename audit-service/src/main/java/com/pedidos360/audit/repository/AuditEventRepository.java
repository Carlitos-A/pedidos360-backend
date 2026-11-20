package com.pedidos360.audit.repository;

import com.pedidos360.audit.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {
    boolean existsByEventId(String eventId);
    @Modifying
    @Query("delete from AuditEvent e where e.occurredAt < :cutoff")
    int deleteByOccurredAtBefore(OffsetDateTime cutoff);
}
