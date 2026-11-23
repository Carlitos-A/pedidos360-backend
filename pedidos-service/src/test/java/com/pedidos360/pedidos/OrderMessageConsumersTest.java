package com.pedidos360.pedidos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.pedidos.messaging.OrderMessageConsumers;
import com.pedidos360.pedidos.model.AuditEvent;
import com.pedidos360.pedidos.repository.AuditEventRepository;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderMessageConsumersTest {
    private final AuditEventRepository audit = mock(AuditEventRepository.class);
    private final Channel channel = mock(Channel.class);
    private final OrderMessageConsumers consumer = new OrderMessageConsumers(new ObjectMapper(), audit);

    @Test void cambioDeEstadoSeAuditaUnaSolaVez() throws Exception {
        Message event = message("{" +
            "\"schemaVersion\":2,\"eventId\":\"event-3\",\"eventType\":\"ORDER_CANCELLED\","
            + "\"auditKey\":\"order-status-3-2\",\"orderId\":3,\"customerId\":\"owner\","
            + "\"customerTenantId\":\"tenant\",\"previousStatus\":\"CREATED\","
            + "\"status\":\"CANCELLED\",\"actorEmail\":\"admin@example.test\","
            + "\"occurredAt\":\"2026-11-19T12:00:00Z\"}", "orders.cancelled", 3);
        when(audit.existsByEventKey("order-status-3-2")).thenReturn(false, true);
        consumer.registrarAuditoria(event, channel);
        consumer.registrarAuditoria(event, channel);
        verify(audit, times(1)).saveAndFlush(argThat((AuditEvent saved) ->
            "ORDER_CANCELLED".equals(saved.getType()) && "admin@example.test".equals(saved.getActorEmail())));
        verify(channel, times(2)).basicAck(3L, false);
    }

    private Message message(String json, String routingKey, long tag) {
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey(routingKey);
        props.setDeliveryTag(tag);
        return new Message(json.getBytes(StandardCharsets.UTF_8), props);
    }
}
