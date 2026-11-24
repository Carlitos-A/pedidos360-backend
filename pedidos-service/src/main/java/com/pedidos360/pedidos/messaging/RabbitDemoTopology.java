package com.pedidos360.pedidos.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Isolated academic demonstration: the demo DLQ has no monitor so it remains visible. */
@Configuration
@ConditionalOnProperty(name = "app.rabbit-demo.enabled", havingValue = "true")
public class RabbitDemoTopology {
    public static final String EXCHANGE = "pedidos360.demo.direct";
    public static final String WORK_QUEUE = "pedidos360.demo.notification";
    public static final String DLQ = "pedidos360.demo.notification.dlq";
    public static final String ROUTING_KEY = "demo.invalid";

    @Bean public DirectExchange rabbitDemoExchange() { return new DirectExchange(EXCHANGE, true, false); }
    @Bean public Queue rabbitDemoQueue() {
        return QueueBuilder.durable(WORK_QUEUE)
            .deadLetterExchange("pedidos360.dead-letter")
            .deadLetterRoutingKey(DLQ).build();
    }
    @Bean public Queue rabbitDemoDlq() { return QueueBuilder.durable(DLQ).build(); }
    @Bean public Binding rabbitDemoBinding() {
        return BindingBuilder.bind(rabbitDemoQueue()).to(rabbitDemoExchange()).with(ROUTING_KEY);
    }
    @Bean public Binding rabbitDemoDlqBinding(@Qualifier("deadLetterExchange") DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(rabbitDemoDlq()).to(deadLetterExchange).with(DLQ);
    }
}
