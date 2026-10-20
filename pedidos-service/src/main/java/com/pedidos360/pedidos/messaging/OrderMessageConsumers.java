package com.pedidos360.pedidos.messaging;

import java.io.IOException;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;

/** Cada caso funcional tiene su listener y confirma o rechaza explícitamente el mensaje. */
@Component
public class OrderMessageConsumers {
    private static final Logger log = LoggerFactory.getLogger(OrderMessageConsumers.class);
    private final ObjectMapper objectMapper;

    public OrderMessageConsumers(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    @RabbitListener(queues = "${pedidos360.messaging.queues.notification}")
    public void procesarNotificacion(Message message, Channel channel) throws IOException {
        procesar("notificación", message, channel);
    }

    @RabbitListener(queues = "${pedidos360.messaging.queues.ticket}")
    public void generarTicket(Message message, Channel channel) throws IOException {
        procesar("ticket", message, channel);
    }

    @RabbitListener(queues = "${pedidos360.messaging.queues.audit}")
    public void registrarAuditoria(Message message, Channel channel) throws IOException {
        procesar("auditoría", message, channel);
    }

    private void procesar(String destino, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            OrderCreatedEvent event = objectMapper.readValue(message.getBody(), OrderCreatedEvent.class);
            if (event.orderId() == null || event.customer() == null || event.customer().isBlank()) {
                throw new IllegalArgumentException("El evento debe incluir orderId y customer");
            }
            channel.basicAck(deliveryTag, false);
            log.info("[RabbitMQ] ACK tarea={} pedido={} messageId={} cliente={}",
                    destino, event.orderId(), message.getMessageProperties().getMessageId(), event.customer());
        } catch (Exception exception) {
            channel.basicNack(deliveryTag, false, false);
            log.warn("[RabbitMQ] NACK tarea={} messageId={} requeue=false; RabbitMQ enruta a DLQ. Causa: {}",
                    destino, message.getMessageProperties().getMessageId(), exception.getMessage());
        }
    }
}
