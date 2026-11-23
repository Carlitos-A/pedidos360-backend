package com.pedidos360.pedidos;

import com.pedidos360.pedidos.messaging.RabbitDeadLetterMonitor;
import com.pedidos360.pedidos.model.RejectedRabbitMessage;
import com.pedidos360.pedidos.repository.RejectedRabbitMessageRepository;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import static org.mockito.Mockito.*;

class RabbitDeadLetterMonitorTest {
    @Test void registraDlqYConfirmaDespues() throws Exception {
        RejectedRabbitMessageRepository repository = mock(RejectedRabbitMessageRepository.class);
        Channel channel = mock(Channel.class);
        MessageProperties props = new MessageProperties();
        props.setMessageId("event-42"); props.setDeliveryTag(7);
        props.setHeader("x-death", List.of(Map.of("queue", "orders.audit", "reason", "rejected")));
        new RabbitDeadLetterMonitor(repository).record(
            new Message("{private}".getBytes(StandardCharsets.UTF_8), props), channel, "orders.audit.dlq");
        var order = inOrder(repository, channel);
        order.verify(repository).saveAndFlush(org.mockito.ArgumentMatchers.argThat((RejectedRabbitMessage entry) ->
            entry.getDlqName().equals("orders.audit.dlq") && entry.getSourceQueue().equals("orders.audit")
            && entry.getDeathReason().equals("rejected") && entry.getPayloadSha256().length() == 64));
        order.verify(channel).basicAck(7, false);
    }

    @Test void falloDeBaseReencolaEnDlq() throws Exception {
        RejectedRabbitMessageRepository repository = mock(RejectedRabbitMessageRepository.class);
        when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
            .thenThrow(new IllegalStateException("db down"));
        Channel channel = mock(Channel.class);
        MessageProperties props = new MessageProperties(); props.setDeliveryTag(8);
        new RabbitDeadLetterMonitor(repository).record(new Message(new byte[0], props), channel, "orders.ticket.dlq");
        verify(channel).basicNack(8, false, true);
        verify(channel, never()).basicAck(8, false);
    }
}
