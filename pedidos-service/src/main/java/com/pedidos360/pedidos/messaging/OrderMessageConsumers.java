package com.pedidos360.pedidos.messaging;

import java.io.IOException;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.Channel;
import com.pedidos360.pedidos.model.AuditEvent;
import com.pedidos360.pedidos.repository.AuditEventRepository;
import java.time.OffsetDateTime;

/** Cada caso funcional tiene su listener y confirma o rechaza explícitamente el mensaje. */
@Component
public class OrderMessageConsumers {
    private static final Logger log = LoggerFactory.getLogger(OrderMessageConsumers.class);
    private final ObjectMapper objectMapper;
    private final AuditEventRepository auditRepository;

    public OrderMessageConsumers(ObjectMapper objectMapper, AuditEventRepository auditRepository) {
        this.objectMapper = objectMapper; this.auditRepository = auditRepository;
    }

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
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            String routingKey = message.getMessageProperties().getReceivedRoutingKey();
            if ("products.updated.audit".equals(routingKey)) {
                var event = objectMapper.readTree(message.getBody());
                String key = event.path("eventId").asText();
                String productId = event.path("productId").asText();
                if (key.isBlank() || productId.isBlank()) throw new IllegalArgumentException("Evento de producto inválido");
                if (!auditRepository.existsByEventKey(key)) auditRepository.saveAndFlush(new AuditEvent(key,
                    "PRODUCT_UPDATED", "Producto " + productId + " actualizado",
                    event.path("actorEmail").asText(null), OffsetDateTime.parse(event.path("occurredAt").asText())));
            } else {
                JsonNode event = orderEvent(message);
                if (event.path("schemaVersion").asInt(1) == 2) {
                    String key = event.path("auditKey").asText();
                    String type = event.path("eventType").asText();
                    if (key.isBlank() || !java.util.List.of("ORDER_CREATED", "ORDER_STATUS_CHANGED", "ORDER_CANCELLED").contains(type))
                        throw new IllegalArgumentException("Evento v2 inválido");
                    if (!auditRepository.existsByEventKey(key)) {
                        String orderId = event.path("orderId").asText();
                        String description = "ORDER_CREATED".equals(type) ? "Pedido " + orderId + " creado"
                            : "Pedido " + orderId + " pasó de " + event.path("previousStatus").asText()
                                + " a " + event.path("status").asText();
                        try {
                            auditRepository.saveAndFlush(new AuditEvent(key, type, description,
                                event.path("actorEmail").asText(null),
                                OffsetDateTime.parse(event.path("occurredAt").asText())));
                        } catch (DataIntegrityViolationException duplicate) {
                            if (!auditRepository.existsByEventKey(key)) throw duplicate;
                        }
                    }
                }
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            channel.basicNack(deliveryTag, false, false);
            log.warn("[RabbitMQ] NACK auditoría messageId={} causa={}", message.getMessageProperties().getMessageId(), exception.getMessage());
        }
    }

    private void procesar(String destino, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            JsonNode event = orderEvent(message);
            String customer = event.path("schemaVersion").asInt(1) == 2
                ? event.path("customerName").asText() : event.path("customer").asText();
            if (customer.isBlank() || event.path("schemaVersion").asInt(1) == 2
                    && !"ORDER_CREATED".equals(event.path("eventType").asText())) {
                throw new IllegalArgumentException("El evento debe incluir orderId y customer");
            }
            channel.basicAck(deliveryTag, false);
            log.info("[RabbitMQ] ACK tarea={} pedido={} messageId={} cliente={}",
                    destino, event.path("orderId").asText(), message.getMessageProperties().getMessageId(), customer);
        } catch (Exception exception) {
            channel.basicNack(deliveryTag, false, false);
            log.warn("[RabbitMQ] NACK tarea={} messageId={} requeue=false; RabbitMQ enruta a DLQ. Causa: {}",
                    destino, message.getMessageProperties().getMessageId(), exception.getMessage());
        }
    }

    private JsonNode orderEvent(Message message) throws IOException {
        JsonNode event = objectMapper.readTree(message.getBody());
        if (event == null || !event.path("orderId").canConvertToLong())
            throw new IllegalArgumentException("Evento de pedido inválido");
        int version = event.path("schemaVersion").asInt(1);
        if (version != 1 && version != 2) throw new IllegalArgumentException("Versión de evento desconocida");
        if (version == 2 && (event.path("eventId").asText().isBlank()
            || event.path("customerId").asText().isBlank()
            || event.path("customerTenantId").asText().isBlank()))
            throw new IllegalArgumentException("Evento v2 incompleto");
        return event;
    }
}
