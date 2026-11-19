package com.pedidos360.pedidos.repository;

import com.pedidos360.pedidos.model.Pedido;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface PedidoRepository extends JpaRepository<Pedido, Long>, JpaSpecificationExecutor<Pedido> {
    Optional<Pedido> findByReservationKey(String reservationKey);
    List<Pedido> findByInventoryState(String inventoryState);
}
