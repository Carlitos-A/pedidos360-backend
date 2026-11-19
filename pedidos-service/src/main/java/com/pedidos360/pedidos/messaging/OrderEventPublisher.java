package com.pedidos360.pedidos.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.pedidos.model.OrderOutboxMessage;
import com.pedidos360.pedidos.model.Pedido;
import com.pedidos360.pedidos.model.PedidoStatusEvent;
import com.pedidos360.pedidos.repository.OrderOutboxRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OrderEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final OrderOutboxRepository outbox;
    private final String directExchange;
    private final String topicExchange;
    private final String orderCreatedKey;
    private final String ticketCreatedKey;
    private final String auditCreatedKey;
    private final String statusChangedKey;
    private final String cancelledKey;

    public OrderEventPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper,
            OrderOutboxRepository outbox,
            @Value("${pedidos360.messaging.exchanges.orders-direct}") String directExchange,
            @Value("${pedidos360.messaging.exchanges.orders-topic}") String topicExchange,
            @Value("${pedidos360.messaging.routing-keys.order-created}") String orderCreatedKey,
            @Value("${pedidos360.messaging.routing-keys.ticket-created}") String ticketCreatedKey,
            @Value("${pedidos360.messaging.routing-keys.audit-created}") String auditCreatedKey,
            @Value("${pedidos360.messaging.routing-keys.status-changed}") String statusChangedKey,
            @Value("${pedidos360.messaging.routing-keys.order-cancelled}") String cancelledKey) {
        this.rabbitTemplate = rabbitTemplate; this.objectMapper = objectMapper; this.outbox = outbox;
        this.directExchange = directExchange; this.topicExchange = topicExchange;
        this.orderCreatedKey = orderCreatedKey; this.ticketCreatedKey = ticketCreatedKey;
        this.auditCreatedKey = auditCreatedKey; this.statusChangedKey = statusChangedKey;
        this.cancelledKey = cancelledKey;
    }

    /** Called inside the same database transaction that confirms the order. */
    public void enqueueCreated(Pedido order, PedidoStatusEvent change) {
        OrderEventV2 event = OrderEventV2.from(order, change, "ORDER_CREATED",
            "order-created-" + order.getId(), order.getRequestActorEmail());
        String payload = json(event);
        outbox.saveAll(List.of(
            new OrderOutboxMessage(event.eventId(), directExchange, orderCreatedKey, payload),
            new OrderOutboxMessage(event.eventId(), topicExchange, ticketCreatedKey, payload),
            new OrderOutboxMessage(event.eventId(), topicExchange, auditCreatedKey, payload)));
    }

    /** Called inside the same database transaction as the status and audit rows. */
    public void enqueueStatus(Pedido order, PedidoStatusEvent change, String auditKey, String actorEmail) {
        boolean cancelled = "CANCELLED".equals(change.getStatus());
        OrderEventV2 event = OrderEventV2.from(order, change,
            cancelled ? "ORDER_CANCELLED" : "ORDER_STATUS_CHANGED", auditKey, actorEmail);
        outbox.save(new OrderOutboxMessage(event.eventId(), topicExchange,
            cancelled ? cancelledKey : statusChangedKey, json(event)));
    }

    @Scheduled(fixedDelayString = "${app.outbox.retry-delay-ms:10000}",
               initialDelayString = "${app.outbox.initial-delay-ms:10000}")
    public void dispatchPending() {
        for (OrderOutboxMessage entry : outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()) {
            try {
                Message message = MessageBuilder.withBody(entry.getPayload().getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .setMessageId(entry.getEventId()).build();
                CorrelationData correlation = new CorrelationData(entry.getId());
                rabbitTemplate.send(entry.getExchangeName(), entry.getRoutingKey(), message, correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirm.isAck() || correlation.getReturned() != null)
                    throw new IllegalStateException("RabbitMQ rechazó el evento: " + confirm.getReason());
                entry.published();
                outbox.saveAndFlush(entry);
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                entry.failed(ex.getMessage());
                try { outbox.saveAndFlush(entry); }
                catch (RuntimeException saveError) { log.error("No se pudo registrar fallo del evento {}", entry.getEventId(), saveError); }
                log.warn("Evento {} pendiente de RabbitMQ: {}", entry.getEventId(), ex.getMessage());
                if (ex instanceof InterruptedException) break;
            }
        }
    }

    private String json(OrderEventV2 event) {
        try { return objectMapper.writeValueAsString(event); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("No se pudo serializar el evento", ex); }
    }
}
