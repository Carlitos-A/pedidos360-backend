package com.pedidos360.pedidos.repository;

import com.pedidos360.pedidos.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    boolean existsByEventKey(String eventKey);
}
