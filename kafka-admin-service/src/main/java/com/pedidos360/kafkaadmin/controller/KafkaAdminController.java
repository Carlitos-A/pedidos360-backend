package com.pedidos360.kafkaadmin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.pedidos360.kafkaadmin.service.KafkaManagementService;
import com.pedidos360.kafkaadmin.service.KafkaManagementService.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/kafka")
@Tag(name = "Kafka Admin", description = "Red privada; requiere JWT Admin y X-Internal-Key")
public class KafkaAdminController {
    private final KafkaManagementService service;
    public KafkaAdminController(KafkaManagementService service) { this.service = service; }
    public record CreateTopic(String name, Integer partitions, Integer replicationFactor, Long retentionMs,
                              String cleanupPolicy) {}
    public record UpdateTopic(Integer partitions, Long retentionMs, String cleanupPolicy) {}

    @GetMapping("/topics")
    @Operation(summary = "Lista tópicos")
    public List<TopicSummary> topics() { return service.topics(); }
    @GetMapping("/topics/{name}")
    @Operation(summary = "Inspecciona tópico, particiones y configuraciones no sensibles")
    public TopicDetails topic(@PathVariable String name) { return service.topic(name); }
    @PostMapping("/topics")
    @Operation(summary = "Crea tópico")
    @ResponseStatus(HttpStatus.CREATED)
    public TopicDetails create(@RequestBody CreateTopic request) {
        if (request == null || request.partitions() == null || request.replicationFactor() == null
            || request.retentionMs() == null || request.cleanupPolicy() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campos obligatorios faltantes");
        return service.create(request.name(), request.partitions(), request.replicationFactor(),
            request.retentionMs(), request.cleanupPolicy());
    }
    @PatchMapping("/topics/{name}")
    @Operation(summary = "Aumenta particiones o cambia retención y limpieza")
    public TopicDetails update(@PathVariable String name, @RequestBody UpdateTopic request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cuerpo requerido");
        return service.update(name, request.partitions(), request.retentionMs(), request.cleanupPolicy());
    }
    @DeleteMapping("/topics/{name}")
    @Operation(summary = "Elimina tópico", description = "Desactivado por defecto; requiere KAFKA_ADMIN_ALLOW_DELETE=true")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        service.delete(name); return ResponseEntity.noContent().build();
    }
    @GetMapping("/groups")
    @Operation(summary = "Lista grupos consumidores")
    public List<GroupSummary> groups() { return service.groups(); }
    @GetMapping("/groups/{groupId}")
    @Operation(summary = "Inspecciona miembros, asignaciones y offsets confirmados")
    public GroupDetails group(@PathVariable String groupId) { return service.group(groupId); }
}
