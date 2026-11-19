package com.pedidos360.pedidos.repository;

import com.pedidos360.pedidos.model.PendingInventoryRelease;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingInventoryReleaseRepository extends JpaRepository<PendingInventoryRelease, Long> {}
