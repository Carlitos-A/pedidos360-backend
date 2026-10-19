package com.pedidos360.pedidos.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Topología declarativa y centralizada. Las colas de trabajo envían rechazos a su DLQ. */
@Configuration
public class RabbitTopology {

    @Bean
    public ObjectMapper messagingObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Value("${pedidos360.messaging.exchanges.orders-direct}")
    private String ordersDirectName;
    @Value("${pedidos360.messaging.exchanges.orders-topic}")
    private String ordersTopicName;
    @Value("${pedidos360.messaging.exchanges.dead-letter}")
    private String deadLetterName;
    @Value("${pedidos360.messaging.queues.notification}")
    private String notificationName;
    @Value("${pedidos360.messaging.queues.notification-dlq}")
    private String notificationDlqName;
    @Value("${pedidos360.messaging.queues.ticket}")
    private String ticketName;
    @Value("${pedidos360.messaging.queues.ticket-dlq}")
    private String ticketDlqName;
    @Value("${pedidos360.messaging.queues.audit}")
    private String auditName;
    @Value("${pedidos360.messaging.queues.audit-dlq}")
    private String auditDlqName;
    @Value("${pedidos360.messaging.routing-keys.order-created}")
    private String orderCreatedKey;
    @Value("${pedidos360.messaging.routing-keys.ticket-created}")
    private String ticketCreatedKey;
    @Value("${pedidos360.messaging.routing-keys.audit-created}")
    private String auditCreatedKey;

    @Bean public DirectExchange ordersDirectExchange() { return new DirectExchange(ordersDirectName, true, false); }
    @Bean public TopicExchange ordersTopicExchange() { return new TopicExchange(ordersTopicName, true, false); }
    @Bean public DirectExchange deadLetterExchange() { return new DirectExchange(deadLetterName, true, false); }

    @Bean public Queue notificationQueue() { return workQueue(notificationName, notificationDlqName); }
    @Bean public Queue notificationDlq() { return QueueBuilder.durable(notificationDlqName).build(); }
    @Bean public Queue ticketQueue() { return workQueue(ticketName, ticketDlqName); }
    @Bean public Queue ticketDlq() { return QueueBuilder.durable(ticketDlqName).build(); }
    @Bean public Queue auditQueue() { return workQueue(auditName, auditDlqName); }
    @Bean public Queue auditDlq() { return QueueBuilder.durable(auditDlqName).build(); }

    @Bean public Binding notificationBinding(@Qualifier("notificationQueue") Queue notificationQueue,
            @Qualifier("ordersDirectExchange") DirectExchange ordersDirectExchange) {
        return BindingBuilder.bind(notificationQueue).to(ordersDirectExchange).with(orderCreatedKey);
    }
    @Bean public Binding ticketBinding(@Qualifier("ticketQueue") Queue ticketQueue,
            @Qualifier("ordersTopicExchange") TopicExchange ordersTopicExchange) {
        return BindingBuilder.bind(ticketQueue).to(ordersTopicExchange).with(ticketCreatedKey);
    }
    @Bean public Binding auditBinding(@Qualifier("auditQueue") Queue auditQueue,
            @Qualifier("ordersTopicExchange") TopicExchange ordersTopicExchange) {
        return BindingBuilder.bind(auditQueue).to(ordersTopicExchange).with(auditCreatedKey);
    }
    @Bean public Binding notificationDlqBinding(@Qualifier("notificationDlq") Queue notificationDlq,
            @Qualifier("deadLetterExchange") DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(notificationDlq).to(deadLetterExchange).with(notificationDlqName);
    }
    @Bean public Binding ticketDlqBinding(@Qualifier("ticketDlq") Queue ticketDlq,
            @Qualifier("deadLetterExchange") DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(ticketDlq).to(deadLetterExchange).with(ticketDlqName);
    }
    @Bean public Binding auditDlqBinding(@Qualifier("auditDlq") Queue auditDlq,
            @Qualifier("deadLetterExchange") DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(auditDlq).to(deadLetterExchange).with(auditDlqName);
    }

    private Queue workQueue(String name, String deadLetterQueue) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(deadLetterName)
                .deadLetterRoutingKey(deadLetterQueue)
                .build();
    }
}
