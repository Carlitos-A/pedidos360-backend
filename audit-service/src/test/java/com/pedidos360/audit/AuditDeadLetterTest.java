package com.pedidos360.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.audit.messaging.AuditEventIngestor;
import com.pedidos360.audit.repository.AuditEventRepository;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuditDeadLetterTest {
    @Test void mensajeInvalidoConfirmaOffsetSoloDespuesDePublicarEnDlt() throws Exception {
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var ingestor = new AuditEventIngestor(new ObjectMapper(), mock(AuditEventRepository.class), kafka,
            "pedidos360.audit.v1", "pedidos360.login.v1", "pedidos360.audit.dlt.v1");
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        var record = new ConsumerRecord<>("pedidos360.audit.v1", 0, 12L, "order:1", "{}");
        when(kafka.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        assertThrows(Exception.class, () -> ingestor.consume(record, acknowledgment));
        verify(acknowledgment, never()).acknowledge();
        when(kafka.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
        ingestor.consume(record, acknowledgment);
        verify(kafka, times(2)).send("pedidos360.audit.dlt.v1", "order:1", "{}");
        verify(acknowledgment).acknowledge();
    }
}
