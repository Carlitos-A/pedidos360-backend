package com.pedidos360.productos.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.productos.model.Producto;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ProductAuditPublisher {
    private static final Logger log = LoggerFactory.getLogger(ProductAuditPublisher.class);
    private final RabbitTemplate rabbit;
    private final ProductAuditOutboxRepository outbox;
    private final ObjectMapper mapper = new ObjectMapper();
    public ProductAuditPublisher(RabbitTemplate rabbit, ProductAuditOutboxRepository outbox) {
        this.rabbit = rabbit; this.outbox = outbox;
    }
    public void updated(Producto product, String actorEmail) {
        outbox.save(new ProductAuditOutbox(UUID.randomUUID().toString(), String.valueOf(product.getId()),
            actorEmail, OffsetDateTime.now()));
    }
    @Scheduled(fixedDelay = 5000)
    public void publishPending() {
        for (ProductAuditOutbox event : outbox.findAll()) {
            try {
                Map<String, String> payload = Map.of("eventId", event.getEventId(), "productId", event.getProductId(),
                    "actorEmail", event.getActorEmail() == null ? "" : event.getActorEmail(),
                    "occurredAt", event.getOccurredAt().toString());
                rabbit.convertAndSend("pedidos360.orders.topic", "products.updated.audit", mapper.writeValueAsString(payload));
                outbox.delete(event);
            } catch (Exception e) {
                log.warn("Auditoría de producto pendiente: {}", event.getEventId(), e);
                return;
            }
        }
    }
}
