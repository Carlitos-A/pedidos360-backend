package com.pedidos360.report.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.report.model.ReportOrder;
import com.pedidos360.report.model.ReportReceipt;
import com.pedidos360.report.repository.ReportOrderRepository;
import com.pedidos360.report.repository.ReportReceiptRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportProjectionWriter {
    private final ReportOrderRepository orders;
    private final ReportReceiptRepository receipts;
    private final ObjectMapper mapper;
    public ReportProjectionWriter(ReportOrderRepository orders, ReportReceiptRepository receipts, ObjectMapper mapper) {
        this.orders = orders; this.receipts = receipts; this.mapper = mapper;
    }
    @Transactional
    public void apply(ReportOrderEvent event) {
        if (receipts.existsById(event.eventId())) return;
        ReportOrder previous = orders.findById(event.orderId()).orElse(null);
        if (previous == null || event.revision() > previous.getRevision()) {
            Instant completedAt = "COMPLETED".equals(event.status()) ? event.occurredAt()
                : previous == null ? null : previous.getCompletedAt();
            String items;
            try { items = mapper.writeValueAsString(event.items()); }
            catch (JsonProcessingException ex) { throw new IllegalStateException("No se pudo guardar el snapshot", ex); }
            orders.save(new ReportOrder(event.orderId(), event.revision(), event.status(), event.total(),
                event.createdAt(), completedAt, items));
        }
        receipts.save(new ReportReceipt(event.eventId()));
    }
}
