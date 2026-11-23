package com.pedidos360.audit.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.audit.model.AuditEvent;
import com.pedidos360.audit.repository.AuditEventRepository;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class AuditEventIngestor {
    private static final Logger log = LoggerFactory.getLogger(AuditEventIngestor.class);
    private static final Set<String> ORDER_TYPES = Set.of("ORDER_CREATED", "ORDER_STATUS_CHANGED",
        "ORDER_CANCELLED", "ORDER_OWNER_CHANGED");
    private static final Set<String> STATUSES = Set.of("CREATED", "ACCEPTED", "IN_PROGRESS", "COMPLETED", "CANCELLED");
    private final ObjectMapper mapper;
    private final AuditEventRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final String auditTopic;
    private final String loginTopic;
    private final String dltTopic;

    public AuditEventIngestor(ObjectMapper mapper, AuditEventRepository repository,
                              KafkaTemplate<String, String> kafka,
                              @Value("${app.audit.topic:pedidos360.audit.v1}") String auditTopic,
                              @Value("${app.audit.login-topic:pedidos360.login.v1}") String loginTopic,
                              @Value("${app.audit.dlt-topic:pedidos360.audit.dlt.v1}") String dltTopic) {
        this.mapper = mapper; this.repository = repository; this.kafka = kafka;
        this.auditTopic = auditTopic; this.loginTopic = loginTopic; this.dltTopic = dltTopic;
    }

    @KafkaListener(topics = {"${app.audit.topic:pedidos360.audit.v1}", "${app.audit.login-topic:pedidos360.login.v1}"},
                   groupId = "${app.audit.group-id:pedidos360-audit}",
                   autoStartup = "${app.audit.kafka-enabled:false}", ackMode = "MANUAL_IMMEDIATE")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) throws Exception {
        try {
            ingest(record.topic(), record.value());
            acknowledgment.acknowledge();
        } catch (InvalidAuditEventException invalid) {
            // A malformed event must be visible for review; do not advance the offset until DLT confirms it.
            DeadLetterPublisher.publish(kafka, dltTopic, record, invalid.getMessage());
            log.warn("Evento de auditoría inválido en {} offset {}: {}",
                record.topic(), record.offset(), invalid.getMessage());
            acknowledgment.acknowledge();
        }
    }

    public void ingest(String topic, String json) {
        JsonNode event;
        try { event = mapper.readTree(json); }
        catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new InvalidAuditEventException("JSON inválido", ex);
        }
        if (event == null || event.path("schemaVersion").asInt() != 1)
            throw new InvalidAuditEventException("Versión inválida");
        String eventId = required(event, "eventId", 255);
        String type = required(event, "type", 40);
        String entityId = required(event, "entityId", 255);
        OffsetDateTime occurredAt;
        try { occurredAt = OffsetDateTime.parse(required(event, "occurredAt", 64)); }
        catch (DateTimeParseException ex) { throw new InvalidAuditEventException("Fecha inválida", ex); }
        if (type.equals("USER_LOGIN")) {
            if (!topic.equals(loginTopic)) throw new InvalidAuditEventException("Origen de login no autorizado");
        } else if (!topic.equals(auditTopic)) {
            throw new InvalidAuditEventException("Tópico de dominio inválido");
        }
        String description;
        if (ORDER_TYPES.contains(type)) {
            if (!entityId.matches("[1-9][0-9]*")) throw new InvalidAuditEventException("ID de pedido inválido");
            if (type.equals("ORDER_OWNER_CHANGED")) {
                description = "Propietario del pedido " + entityId + " actualizado";
            } else {
                String current = required(event, "status", 40);
                if (!STATUSES.contains(current)) throw new InvalidAuditEventException("Estado inválido");
                if (type.equals("ORDER_CREATED")) {
                    if (!current.equals("CREATED")) throw new InvalidAuditEventException("Creación inválida");
                    description = "Pedido " + entityId + " creado";
                } else {
                    String previous = required(event, "previousStatus", 40);
                    if (!STATUSES.contains(previous)) throw new InvalidAuditEventException("Estado previo inválido");
                    if (type.equals("ORDER_CANCELLED") && !current.equals("CANCELLED"))
                        throw new InvalidAuditEventException("Cancelación inválida");
                    if (type.equals("ORDER_STATUS_CHANGED") && current.equals("CANCELLED"))
                        throw new InvalidAuditEventException("Tipo de cancelación inválido");
                    description = "Pedido " + entityId + " pasó de " + previous + " a " + current;
                }
            }
        } else if (type.equals("PRODUCT_UPDATED")) {
            if (!entityId.matches("[1-9][0-9]*")) throw new InvalidAuditEventException("ID de producto inválido");
            description = "Producto " + entityId + " actualizado";
        } else if (type.equals("USER_LOGIN")) {
            if (optionalBounded(event, "actorEmail", 255) == null || optionalBounded(event, "actorId", 255) == null
                || optionalBounded(event, "actorTenantId", 255) == null)
                throw new InvalidAuditEventException("Login sin identidad verificable");
            description = "Inicio de sesión confirmado";
        } else throw new InvalidAuditEventException("Tipo inválido");
        if (!repository.existsByEventId(eventId)) repository.saveAndFlush(new AuditEvent(eventId, type,
            description, optionalBounded(event, "actorEmail", 255), optionalBounded(event, "actorId", 255),
            optionalBounded(event, "actorTenantId", 255), occurredAt));
    }

    private String required(JsonNode node, String field, int max) {
        String value = optional(node, field);
        if (value == null || value.length() > max) throw new InvalidAuditEventException("Campo inválido: " + field);
        return value;
    }
    private String optional(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) return null;
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }
    private String optionalBounded(JsonNode node, String field, int max) {
        String value = optional(node, field);
        if (value != null && value.length() > max) throw new InvalidAuditEventException("Campo inválido: " + field);
        return value;
    }
    public static class InvalidAuditEventException extends RuntimeException {
        public InvalidAuditEventException(String message) { super(message); }
        public InvalidAuditEventException(String message, Throwable cause) { super(message, cause); }
    }
}
