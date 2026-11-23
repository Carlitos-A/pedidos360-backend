package com.pedidos360.pedidos.repository;

import com.pedidos360.pedidos.model.RejectedRabbitMessage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RejectedRabbitMessageRepository extends JpaRepository<RejectedRabbitMessage, Long> {
    boolean existsByFingerprint(String fingerprint);
}
