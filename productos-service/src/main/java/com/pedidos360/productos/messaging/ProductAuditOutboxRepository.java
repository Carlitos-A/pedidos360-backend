package com.pedidos360.productos.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductAuditOutboxRepository extends JpaRepository<ProductAuditOutbox, String> {
    List<ProductAuditOutbox> findTop100ByOrderByOccurredAtAsc();
}
