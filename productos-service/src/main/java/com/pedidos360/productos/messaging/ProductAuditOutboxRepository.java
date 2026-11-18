package com.pedidos360.productos.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductAuditOutboxRepository extends JpaRepository<ProductAuditOutbox, String> {}
