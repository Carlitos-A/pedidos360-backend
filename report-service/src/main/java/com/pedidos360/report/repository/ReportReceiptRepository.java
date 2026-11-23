package com.pedidos360.report.repository;

import com.pedidos360.report.model.ReportReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ReportReceiptRepository extends JpaRepository<ReportReceipt, String> {
    Optional<ReportReceipt> findTopByOrderByProcessedAtDesc();
}
