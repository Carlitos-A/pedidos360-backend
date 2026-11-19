package com.pedidos360.pedidos.repository;

import com.pedidos360.pedidos.model.OrderOutboxMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderOutboxRepository extends JpaRepository<OrderOutboxMessage, String> {
    List<OrderOutboxMessage> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
}
