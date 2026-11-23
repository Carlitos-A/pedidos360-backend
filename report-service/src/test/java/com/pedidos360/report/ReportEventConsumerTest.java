package com.pedidos360.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.report.messaging.ReportEventConsumer;
import com.pedidos360.report.messaging.ReportProjectionWriter;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportEventConsumerTest {
    @Test void eventoInvalidoSeConfirmaSoloDespuesDeDLT() throws Exception {
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        ReportProjectionWriter writer = mock(ReportProjectionWriter.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        var consumer = new ReportEventConsumer(writer, new ObjectMapper(), kafka, "report.dlt");
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        consumer.consume(new ConsumerRecord<>("orders.events", 0, 4L, "order:42", "{}"), ack);
        var sequence = inOrder(kafka, ack);
        @SuppressWarnings("unchecked") ArgumentCaptor<ProducerRecord<String, String>> sent =
            ArgumentCaptor.forClass(ProducerRecord.class);
        sequence.verify(kafka).send(sent.capture());
        sequence.verify(ack).acknowledge();
        assertEquals("report.dlt", sent.getValue().topic());
        assertEquals("order:42", sent.getValue().key());
        assertEquals("{}", sent.getValue().value());
        assertNotNull(sent.getValue().headers().lastHeader("pedidos360.dlt.source-offset"));
        verifyNoInteractions(writer);
    }
}
