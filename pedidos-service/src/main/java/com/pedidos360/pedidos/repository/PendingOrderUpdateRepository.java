package com.pedidos360.pedidos.repository;

import com.pedidos360.pedidos.model.PendingOrderUpdate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingOrderUpdateRepository extends JpaRepository<PendingOrderUpdate, Long> {}
