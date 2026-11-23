package com.pedidos360.audit;

import com.pedidos360.audit.messaging.DeadLetterMonitor;
import com.pedidos360.audit.model.RejectedKafkaEvent;
import com.pedidos360.audit.repository.RejectedKafkaEventRepository;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeadLetterMonitorTest {
    @Test void registraMetadatosAntesDeConfirmarOffset() {
        RejectedKafkaEventRepository repository = mock(RejectedKafkaEventRepository.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        var record = new ConsumerRecord<>("audit.dlt", 1, 17L, "key", "{private}");
        record.headers().add("pedidos360.dlt.source-topic", "audit.events".getBytes(StandardCharsets.UTF_8));
        record.headers().add("pedidos360.dlt.source-partition", "2".getBytes(StandardCharsets.UTF_8));
        record.headers().add("pedidos360.dlt.source-offset", "42".getBytes(StandardCharsets.UTF_8));
        record.headers().add("pedidos360.dlt.cause", "Versión inválida".getBytes(StandardCharsets.UTF_8));
        new DeadLetterMonitor(repository).consume(record, acknowledgment);
        var order = inOrder(repository, acknowledgment);
        order.verify(repository).saveAndFlush(org.mockito.ArgumentMatchers.argThat((RejectedKafkaEvent event) ->
            event.getDltTopic().equals("audit.dlt") && event.getDltPartition() == 1
            && event.getDltOffset() == 17 && event.getSourceTopic().equals("audit.events")
            && event.getSourcePartition() == 2 && event.getSourceOffset() == 42
            && event.getCause().equals("Versión inválida") && event.getPayloadSha256().length() == 64));
        order.verify(acknowledgment).acknowledge();
    }

    @Test void falloDeBaseNoConfirmaOffset() {
        RejectedKafkaEventRepository repository = mock(RejectedKafkaEventRepository.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
            .thenThrow(new IllegalStateException("db down"));
        assertThrows(IllegalStateException.class, () -> new DeadLetterMonitor(repository)
            .consume(new ConsumerRecord<>("audit.dlt", 0, 1L, "key", "{}"), acknowledgment));
        verifyNoInteractions(acknowledgment);
    }
}
