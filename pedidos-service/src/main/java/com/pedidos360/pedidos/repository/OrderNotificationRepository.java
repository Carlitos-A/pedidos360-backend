package com.pedidos360.pedidos.repository;

import com.pedidos360.pedidos.model.OrderNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderNotificationRepository extends JpaRepository<OrderNotification, Long> {
    boolean existsByEventId(String eventId);
    @Query(value = "select n from OrderNotification n, Pedido p where p.id = n.orderId "
        + "and p.customerId = :oid and p.customerTenantId = :tid "
        + "and n.customerId = :oid and n.customerTenantId = :tid",
        countQuery = "select count(n) from OrderNotification n, Pedido p where p.id = n.orderId "
        + "and p.customerId = :oid and p.customerTenantId = :tid "
        + "and n.customerId = :oid and n.customerTenantId = :tid")
    Page<OrderNotification> findVisibleToCustomer(@Param("oid") String oid,
        @Param("tid") String tid, Pageable pageable);
}
