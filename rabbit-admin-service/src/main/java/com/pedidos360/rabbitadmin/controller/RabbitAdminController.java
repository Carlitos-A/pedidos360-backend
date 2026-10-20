package com.pedidos360.rabbitadmin.controller;

import com.pedidos360.rabbitadmin.service.RabbitAdminService;
import com.pedidos360.rabbitadmin.service.RabbitAdminService.ExchangeInfo;
import com.pedidos360.rabbitadmin.service.RabbitAdminService.BindingInfo;
import com.pedidos360.rabbitadmin.service.RabbitAdminService.QueueInfo;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class RabbitAdminController {
    private final RabbitAdminService service;
    public RabbitAdminController(RabbitAdminService service) { this.service = service; }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("service", "rabbit-admin-service", "status", "UP"); }

    @PostMapping("/queues")
    public ResponseEntity<QueueInfo> crearCola(@RequestBody CreateQueueRequest request) {
        if (request == null || request.durable() == null) {
            throw new IllegalArgumentException("El campo durable es obligatorio");
        }
        QueueInfo queue = service.crearCola(request.name(), request.durable(),
                request.deadLetterExchange(), request.deadLetterRoutingKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(queue);
    }

    @DeleteMapping("/queues/{name}")
    public ResponseEntity<Void> eliminarCola(@PathVariable String name) {
        service.eliminarCola(name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/exchanges")
    public ResponseEntity<ExchangeInfo> crearExchange(@RequestBody CreateExchangeRequest request) {
        if (request == null || request.durable() == null) {
            throw new IllegalArgumentException("El campo durable es obligatorio");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.crearExchange(request.name(), request.type(), request.durable()));
    }

    @DeleteMapping("/exchanges/{name}")
    public ResponseEntity<Void> eliminarExchange(@PathVariable String name) {
        service.eliminarExchange(name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bindings")
    public ResponseEntity<BindingInfo> crearBinding(@RequestBody BindingRequest request) {
        if (request == null) throw new IllegalArgumentException("El cuerpo de la solicitud es obligatorio");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.crearBinding(request.queue(), request.exchange(), request.routingKey()));
    }

    @DeleteMapping("/bindings")
    public ResponseEntity<Void> eliminarBinding(@RequestBody BindingRequest request) {
        if (request == null) throw new IllegalArgumentException("El cuerpo de la solicitud es obligatorio");
        service.eliminarBinding(request.queue(), request.exchange(), request.routingKey());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    public record CreateQueueRequest(String name, Boolean durable,
                                     String deadLetterExchange, String deadLetterRoutingKey) { }
    public record CreateExchangeRequest(String name, String type, Boolean durable) { }
    public record BindingRequest(String queue, String exchange, String routingKey) { }
}
