package com.pedidos360.pedidos.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.pedidos.model.Pedido;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OrderEventPublisher {
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String directExchange;
    private final String topicExchange;
    private final String orderCreatedKey;
    private final String ticketCreatedKey;
    private final String auditCreatedKey;

    public OrderEventPublisher(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            @Value("${pedidos360.messaging.exchanges.orders-direct}") String directExchange,
            @Value("${pedidos360.messaging.exchanges.orders-topic}") String topicExchange,
            @Value("${pedidos360.messaging.routing-keys.order-created}") String orderCreatedKey,
            @Value("${pedidos360.messaging.routing-keys.ticket-created}") String ticketCreatedKey,
            @Value("${pedidos360.messaging.routing-keys.audit-created}") String auditCreatedKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.directExchange = directExchange;
        this.topicExchange = topicExchange;
        this.orderCreatedKey = orderCreatedKey;
        this.ticketCreatedKey = ticketCreatedKey;
        this.auditCreatedKey = auditCreatedKey;
    }

    public void publicarPedidoCreado(Pedido pedido) {
        OrderCreatedEvent event = new OrderCreatedEvent(pedido.getId(), pedido.getCliente(),
                pedido.getDetalle(), pedido.getTotal(), pedido.getEstado(), pedido.getFecha());
        Message message = toMessage(event);
        rabbitTemplate.send(directExchange, orderCreatedKey, message);
        rabbitTemplate.send(topicExchange, ticketCreatedKey, message);
        rabbitTemplate.send(topicExchange, auditCreatedKey, message);
    }

    private Message toMessage(OrderCreatedEvent event) {
        try {
            return MessageBuilder.withBody(objectMapper.writeValueAsBytes(event))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .setMessageId("order-" + event.orderId())
                    .build();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("No se pudo serializar el evento de pedido", exception);
        }
    }
}
