package com.pedidos360.pedidos.repository;

import com.pedidos360.pedidos.model.OrderTicket;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderTicketRepository extends JpaRepository<OrderTicket, Long> {
    boolean existsByEventId(String eventId);
    boolean existsByOrderId(Long orderId);
    Optional<OrderTicket> findByOrderId(Long orderId);
}
