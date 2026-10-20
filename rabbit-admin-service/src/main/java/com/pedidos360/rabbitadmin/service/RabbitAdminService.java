package com.pedidos360.rabbitadmin.service;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Service;

@Service
public class RabbitAdminService {
    private static final String MANAGED_PREFIX = "pedidos360.dynamic.";
    private final RabbitAdmin rabbitAdmin;

    public RabbitAdminService(RabbitAdmin rabbitAdmin) { this.rabbitAdmin = rabbitAdmin; }

    public QueueInfo crearCola(String name, boolean durable, String deadLetterExchange, String deadLetterKey) {
        validarNombreDinamico(name, "colas");
        QueueBuilder builder = QueueBuilder.durable(name);
        if (!durable) builder = QueueBuilder.nonDurable(name);
        if (deadLetterExchange != null && !deadLetterExchange.isBlank()) {
            validarNombre(deadLetterExchange);
            if (deadLetterKey == null || deadLetterKey.isBlank()) {
                throw new IllegalArgumentException("deadLetterRoutingKey es obligatorio al configurar DLX");
            }
            builder.deadLetterExchange(deadLetterExchange).deadLetterRoutingKey(deadLetterKey.trim());
        } else if (deadLetterKey != null && !deadLetterKey.isBlank()) {
            throw new IllegalArgumentException("No se puede configurar routing key sin deadLetterExchange");
        }
        Queue queue = builder.build();
        rabbitAdmin.declareQueue(queue);
        return new QueueInfo(queue.getName(), queue.isDurable(), queue.getArguments());
    }

    public void eliminarCola(String name) {
        validarNombreDinamico(name, "colas");
        if (rabbitAdmin.getQueueProperties(name) == null) throw new IllegalArgumentException("La cola no existe: " + name);
        rabbitAdmin.deleteQueue(name);
    }

    public ExchangeInfo crearExchange(String name, String type, boolean durable) {
        validarNombre(name);
        if (!name.startsWith(MANAGED_PREFIX)) {
            throw new IllegalArgumentException("Los exchanges creados por API deben usar prefijo " + MANAGED_PREFIX);
        }
        Exchange exchange = switch (type == null ? "" : type.trim().toLowerCase()) {
            case "direct" -> new DirectExchange(name, durable, false);
            case "topic" -> new TopicExchange(name, durable, false);
            case "fanout" -> new FanoutExchange(name, durable, false);
            default -> throw new IllegalArgumentException("type debe ser direct, topic o fanout");
        };
        rabbitAdmin.declareExchange(exchange);
        return new ExchangeInfo(exchange.getName(), exchange.getType(), exchange.isDurable());
    }

    public void eliminarExchange(String name) {
        validarNombre(name);
        if (!name.startsWith(MANAGED_PREFIX)) {
            throw new IllegalArgumentException("Solo se permite borrar exchanges dinámicos con prefijo " + MANAGED_PREFIX);
        }
        rabbitAdmin.deleteExchange(name);
    }

    public BindingInfo crearBinding(String queueName, String exchangeName, String routingKey) {
        Binding binding = bindingDinamico(queueName, exchangeName, routingKey);
        rabbitAdmin.declareBinding(binding);
        return new BindingInfo(queueName, exchangeName, routingKey);
    }

    public void eliminarBinding(String queueName, String exchangeName, String routingKey) {
        rabbitAdmin.removeBinding(bindingDinamico(queueName, exchangeName, routingKey));
    }

    private Binding bindingDinamico(String queueName, String exchangeName, String routingKey) {
        validarNombreDinamico(queueName, "colas");
        validarNombreDinamico(exchangeName, "exchanges");
        if (routingKey == null || routingKey.length() > 255
                || routingKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("routingKey es obligatorio y debe tener hasta 255 caracteres sin controles");
        }
        // La clave vacía es válida para un exchange fanout.
        return new Binding(queueName, Binding.DestinationType.QUEUE,
                exchangeName, routingKey, null);
    }

    private void validarNombre(String name) {
        if (name == null || name.isBlank() || name.length() > 180
                || !name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")) {
            throw new IllegalArgumentException("Nombre vacío o inválido (use letras, números, punto, guion o guion bajo)");
        }
    }

    private void validarNombreDinamico(String name, String resource) {
        validarNombre(name);
        if (!name.startsWith(MANAGED_PREFIX)) {
            throw new IllegalArgumentException("Los nombres de " + resource + " dinámicos deben usar prefijo " + MANAGED_PREFIX);
        }
    }

    public record QueueInfo(String name, boolean durable, java.util.Map<String, Object> arguments) { }
    public record ExchangeInfo(String name, String type, boolean durable) { }
    public record BindingInfo(String queue, String exchange, String routingKey) { }
}
