package com.pedidos360.pedidos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pedidos360.pedidos.messaging.OptionalOrderWorkConsumers;
import com.pedidos360.pedidos.messaging.OrderWorkEvent;
import com.pedidos360.pedidos.messaging.OrderWorkService;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OptionalOrderWorkConsumersTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final OrderWorkService service = mock(OrderWorkService.class);
    private final Channel channel = mock(Channel.class);
    private final OptionalOrderWorkConsumers consumer = new OptionalOrderWorkConsumers(mapper, service, 3, 0);

    @Test void eventoValidoPersisteAntesDeAck() throws Exception {
        consumer.notifyCustomer(message(validEvent(), 3), channel);
        var order = inOrder(service, channel);
        order.verify(service).notifyCustomer(org.mockito.ArgumentMatchers.argThat((OrderWorkEvent event) ->
            event.orderId() == 7 && event.total() == 2500 && event.customerId().equals("owner")));
        order.verify(channel).basicAck(3, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test void eventoLegadoSinPropietarioVaADlq() throws Exception {
        consumer.issueTicket(message("{\"orderId\":7,\"customer\":\"Antiguo\"}", 4), channel);
        verify(channel).basicNack(4, false, false);
        verifyNoInteractions(service);
    }

    @Test void falloTransitorioSeReintentaYLuegoVaADlq() throws Exception {
        doThrow(new IllegalStateException("db down")).when(service).issueTicket(any());
        consumer.issueTicket(message(validEvent(), 5), channel);
        verify(service, times(3)).issueTicket(any());
        verify(channel).basicNack(5, false, false);
        verify(channel, never()).basicAck(5, false);
    }

    private Message message(String json, long tag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(tag); properties.setMessageId("event-7");
        return new Message(json.getBytes(StandardCharsets.UTF_8), properties);
    }
    private String validEvent() {
        return """
            {"schemaVersion":2,"eventId":"event-7","eventType":"ORDER_CREATED",
             "orderId":7,"customerName":"Cliente","customerId":"owner",
             "customerTenantId":"tenant","total":2500,"status":"CREATED",
             "createdAt":"2026-11-21T12:00:00Z","occurredAt":"2026-11-21T12:00:00Z",
             "items":[{"productId":"9","name":"Producto","quantity":2,"unitPrice":1250}]}
            """;
    }
}
