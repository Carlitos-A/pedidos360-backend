package com.pedidos360.pedidos.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.pedidos.model.OrderOutboxMessage;
import com.pedidos360.pedidos.model.Pedido;
import com.pedidos360.pedidos.model.PedidoStatusEvent;
import com.pedidos360.pedidos.model.OrderActor;
import com.pedidos360.pedidos.repository.OrderOutboxRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OrderEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);
    private final RabbitTemplate rabbitTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OrderOutboxRepository outbox;
    private final String directExchange;
    private final String topicExchange;
    private final String orderCreatedKey;
    private final String ticketCreatedKey;
    private final String auditCreatedKey;
    private final String statusChangedKey;
    private final String cancelledKey;
    private final boolean kafkaAuditEnabled;
    private final String kafkaAuditTopic;

    public OrderEventPublisher(RabbitTemplate rabbitTemplate, KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
            OrderOutboxRepository outbox,
            @Value("${pedidos360.messaging.exchanges.orders-direct}") String directExchange,
            @Value("${pedidos360.messaging.exchanges.orders-topic}") String topicExchange,
            @Value("${pedidos360.messaging.routing-keys.order-created}") String orderCreatedKey,
            @Value("${pedidos360.messaging.routing-keys.ticket-created}") String ticketCreatedKey,
            @Value("${pedidos360.messaging.routing-keys.audit-created}") String auditCreatedKey,
            @Value("${pedidos360.messaging.routing-keys.status-changed}") String statusChangedKey,
            @Value("${pedidos360.messaging.routing-keys.order-cancelled}") String cancelledKey,
            @Value("${app.audit.kafka-enabled:false}") boolean kafkaAuditEnabled,
            @Value("${app.audit.topic:pedidos360.audit.v1}") String kafkaAuditTopic) {
        this.rabbitTemplate = rabbitTemplate; this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper; this.outbox = outbox;
        this.directExchange = directExchange; this.topicExchange = topicExchange;
        this.orderCreatedKey = orderCreatedKey; this.ticketCreatedKey = ticketCreatedKey;
        this.auditCreatedKey = auditCreatedKey; this.statusChangedKey = statusChangedKey;
        this.cancelledKey = cancelledKey;
        this.kafkaAuditEnabled = kafkaAuditEnabled; this.kafkaAuditTopic = kafkaAuditTopic;
    }

    /** Called inside the same database transaction that confirms the order. */
    public void enqueueCreated(Pedido order, PedidoStatusEvent change) {
        OrderEventV2 event = OrderEventV2.from(order, change, "ORDER_CREATED",
            "order-created-" + order.getId(), order.getRequestActorEmail());
        String payload = json(event);
        outbox.saveAll(List.of(
            new OrderOutboxMessage(event.eventId(), directExchange, orderCreatedKey, payload),
            new OrderOutboxMessage(event.eventId(), topicExchange, ticketCreatedKey, payload),
            auditDelivery(order, change, event, auditCreatedKey)));
    }

    /** Called inside the same database transaction as the status and audit rows. */
    public void enqueueStatus(Pedido order, PedidoStatusEvent change, String auditKey, String actorEmail) {
        boolean cancelled = "CANCELLED".equals(change.getStatus());
        OrderEventV2 event = OrderEventV2.from(order, change,
            cancelled ? "ORDER_CANCELLED" : "ORDER_STATUS_CHANGED", auditKey, actorEmail);
        outbox.save(auditDelivery(order, change, event, cancelled ? cancelledKey : statusChangedKey));
    }

    /** Owner changes were local-only before Kafka; keep the same key as the legacy audit row. */
    public void enqueueOwnerChanged(Pedido order, String auditKey, OrderActor actor) {
        if (!kafkaAuditEnabled) return;
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("schemaVersion", 1);
        audit.put("eventId", auditKey);
        audit.put("type", "ORDER_OWNER_CHANGED");
        audit.put("entityId", String.valueOf(order.getId()));
        audit.put("occurredAt", order.getUpdatedAt().toInstant().toString());
        audit.put("actorId", actor.id());
        audit.put("actorTenantId", actor.tenantId());
        audit.put("actorEmail", actor.email());
        try { outbox.save(new OrderOutboxMessage(auditKey, "kafka", kafkaAuditTopic,
            objectMapper.writeValueAsString(audit))); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("No se pudo serializar auditoría", ex); }
    }

    @Scheduled(fixedDelayString = "${app.outbox.retry-delay-ms:10000}",
               initialDelayString = "${app.outbox.initial-delay-ms:10000}")
    public void dispatchPending() {
        for (OrderOutboxMessage entry : outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()) {
            try {
                if ("kafka".equals(entry.getExchangeName())) {
                    String orderId = objectMapper.readTree(entry.getPayload()).path("entityId").asText();
                    if (orderId.isBlank()) throw new IllegalStateException("Evento Kafka sin pedido");
                    kafkaTemplate.send(entry.getRoutingKey(), "order:" + orderId, entry.getPayload()).get(10, TimeUnit.SECONDS);
                } else {
                    Message message = MessageBuilder.withBody(entry.getPayload().getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                        .setMessageId(entry.getEventId()).build();
                    CorrelationData correlation = new CorrelationData(entry.getId());
                    rabbitTemplate.send(entry.getExchangeName(), entry.getRoutingKey(), message, correlation);
                    CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                    if (!confirm.isAck() || correlation.getReturned() != null)
                        throw new IllegalStateException("RabbitMQ rechazó el evento: " + confirm.getReason());
                }
                entry.published();
                outbox.saveAndFlush(entry);
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                entry.failed(ex.getMessage());
                try { outbox.saveAndFlush(entry); }
                catch (RuntimeException saveError) { log.error("No se pudo registrar fallo del evento {}", entry.getEventId(), saveError); }
                log.warn("Evento {} pendiente de {}: {}", entry.getEventId(), entry.getExchangeName(), ex.getMessage());
                // Keep later status events for the same order behind an earlier failed Kafka event.
                if (ex instanceof InterruptedException || "kafka".equals(entry.getExchangeName())) break;
            }
        }
    }

    private OrderOutboxMessage auditDelivery(Pedido order, PedidoStatusEvent change,
                                             OrderEventV2 event, String legacyRoutingKey) {
        if (!kafkaAuditEnabled)
            return new OrderOutboxMessage(event.eventId(), topicExchange, legacyRoutingKey, json(event));
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("schemaVersion", 1);
        audit.put("eventId", event.auditKey());
        audit.put("type", event.eventType());
        audit.put("entityId", String.valueOf(order.getId()));
        audit.put("occurredAt", change.getChangedAt().toInstant().toString());
        audit.put("actorId", change.getActorId());
        audit.put("actorTenantId", change.getActorTenantId());
        audit.put("actorEmail", event.actorEmail());
        if (change.getPreviousStatus() != null) audit.put("previousStatus", change.getPreviousStatus());
        audit.put("status", change.getStatus());
        try { return new OrderOutboxMessage(event.auditKey(), "kafka", kafkaAuditTopic,
            objectMapper.writeValueAsString(audit)); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("No se pudo serializar auditoría", ex); }
    }

    private String json(OrderEventV2 event) {
        try { return objectMapper.writeValueAsString(event); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("No se pudo serializar el evento", ex); }
    }
}
