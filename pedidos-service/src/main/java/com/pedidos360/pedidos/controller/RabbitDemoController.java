package com.pedidos360.pedidos.controller;

import com.pedidos360.pedidos.messaging.RabbitDemoTopology;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin-only exercise routed through the normal API; never accepts a caller-supplied payload. */
@RestController
@RequestMapping("/api/orders/demo/rabbit")
@ConditionalOnProperty(name = "app.rabbit-demo.enabled", havingValue = "true")
@Tag(name = "Demostración RabbitMQ", description = "Admin; genera un rechazo aislado para mostrar la DLQ")
public class RabbitDemoController {
    private final RabbitTemplate rabbit;
    public RabbitDemoController(RabbitTemplate rabbit) { this.rabbit = rabbit; }

    public record Request(String action) {}
    public record Result(String action, String messageId, String queue) {}

    @PostMapping
    @Operation(summary = "Envía un mensaje inválido o vacía la DLQ de demostración")
    public Result run(@RequestBody Request request) {
        if (request == null || request.action() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Acción requerida");
        if ("clear".equals(request.action())) {
            rabbit.execute(channel -> { channel.queuePurge(RabbitDemoTopology.DLQ); return null; });
            return new Result("clear", null, RabbitDemoTopology.DLQ);
        }
        if (!"reject".equals(request.action()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Acción desconocida");
        String id = "rabbit-demo-" + UUID.randomUUID();
        var message = MessageBuilder.withBody("{invalid".getBytes(StandardCharsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .setMessageId(id).build();
        try {
            var correlation = new CorrelationData(id);
            rabbit.send(RabbitDemoTopology.EXCHANGE, RabbitDemoTopology.ROUTING_KEY, message, correlation);
            var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null)
                throw new IllegalStateException("RabbitMQ no confirmó la publicación");
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No se pudo publicar la prueba", failure);
        }
        return new Result("reject", id, RabbitDemoTopology.DLQ);
    }
}
