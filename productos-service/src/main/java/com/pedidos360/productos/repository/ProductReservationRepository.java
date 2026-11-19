package com.pedidos360.productos.repository;

import com.pedidos360.productos.model.ProductReservation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductReservationRepository extends JpaRepository<ProductReservation, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProductReservation r where r.id = :id")
    Optional<ProductReservation> lockById(@Param("id") String id);
}
