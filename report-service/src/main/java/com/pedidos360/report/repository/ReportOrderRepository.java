package com.pedidos360.report.repository;

import com.pedidos360.report.model.ReportOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface ReportOrderRepository extends JpaRepository<ReportOrder, Long> {
    List<ReportOrder> findByCreatedAtGreaterThanEqual(Instant start);
    List<ReportOrder> findByStatusAndCompletedAtGreaterThanEqual(String status, Instant start);
}
