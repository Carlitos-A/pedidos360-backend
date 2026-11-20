package com.pedidos360.productos.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.productos.model.Producto;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ProductAuditPublisher {
    private static final Logger log = LoggerFactory.getLogger(ProductAuditPublisher.class);
    private final RabbitTemplate rabbit;
    private final KafkaTemplate<String, String> kafka;
    private final ProductAuditOutboxRepository outbox;
    private final boolean kafkaEnabled;
    private final String topic;
    private final ObjectMapper mapper = new ObjectMapper();
    public ProductAuditPublisher(RabbitTemplate rabbit, KafkaTemplate<String, String> kafka,
                                 ProductAuditOutboxRepository outbox,
                                 @Value("${app.audit.kafka-enabled:false}") boolean kafkaEnabled,
                                 @Value("${app.audit.topic:pedidos360.audit.v1}") String topic) {
        this.rabbit = rabbit; this.kafka = kafka; this.outbox = outbox;
        this.kafkaEnabled = kafkaEnabled; this.topic = topic;
    }
    public void updated(Producto product, Jwt jwt) {
        if (jwt == null || jwt.getClaimAsString("oid") == null || jwt.getClaimAsString("oid").isBlank()
            || jwt.getClaimAsString("tid") == null || jwt.getClaimAsString("tid").isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Token sin identidad de actor");
        String email = jwt.getClaimAsString("preferred_username");
        if (email == null) email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Token sin correo de actor");
        enqueue(product, email, jwt.getClaimAsString("oid"), jwt.getClaimAsString("tid"));
    }
    public void updated(Producto product, String actorEmail) {
        enqueue(product, actorEmail, null, null);
    }
    private void enqueue(Producto product, String actorEmail, String actorId, String actorTenantId) {
        outbox.save(new ProductAuditOutbox(UUID.randomUUID().toString(), String.valueOf(product.getId()),
            actorEmail, actorId, actorTenantId, OffsetDateTime.now(), kafkaEnabled ? "kafka" : "rabbit"));
    }
    @Scheduled(fixedDelay = 5000)
    public void publishPending() {
        for (ProductAuditOutbox event : outbox.findTop100ByOrderByOccurredAtAsc()) {
            try {
                if ("kafka".equals(event.getDestination())) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("schemaVersion", 1);
                    payload.put("eventId", event.getEventId());
                    payload.put("type", "PRODUCT_UPDATED");
                    payload.put("entityId", event.getProductId());
                    payload.put("occurredAt", event.getOccurredAt().toInstant().toString());
                    payload.put("actorEmail", event.getActorEmail());
                    payload.put("actorId", event.getActorId());
                    payload.put("actorTenantId", event.getActorTenantId());
                    kafka.send(topic, "product:" + event.getProductId(), mapper.writeValueAsString(payload))
                        .get(10, TimeUnit.SECONDS);
                } else {
                    Map<String, String> payload = Map.of("eventId", event.getEventId(), "productId", event.getProductId(),
                        "actorEmail", event.getActorEmail() == null ? "" : event.getActorEmail(),
                        "occurredAt", event.getOccurredAt().toString());
                    CorrelationData correlation = new CorrelationData(event.getEventId());
                    rabbit.convertAndSend("pedidos360.orders.topic", "products.updated.audit",
                        mapper.writeValueAsString(payload), correlation);
                    CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                    if (!confirm.isAck() || correlation.getReturned() != null)
                        throw new IllegalStateException("RabbitMQ rechazó el evento: " + confirm.getReason());
                }
                outbox.delete(event);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("Auditoría de producto pendiente: {}", event.getEventId(), e);
                return;
            }
        }
    }
}
