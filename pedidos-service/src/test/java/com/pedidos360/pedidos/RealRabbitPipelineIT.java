package com.pedidos360.pedidos;

import com.pedidos360.pedidos.messaging.OrderEventPublisher;
import com.pedidos360.pedidos.messaging.OrderWorkService;
import com.pedidos360.pedidos.model.OrderOutboxMessage;
import com.pedidos360.pedidos.repository.OrderNotificationRepository;
import com.pedidos360.pedidos.repository.OrderOutboxRepository;
import com.pedidos360.pedidos.repository.OrderTicketRepository;
import com.pedidos360.pedidos.repository.RejectedRabbitMessageRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** Runs only with an external RabbitMQ broker; no embedded broker or mocked transport. */
@EnabledIfEnvironmentVariable(named = "RUN_BROKER_IT", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {"app.outbox.initial-delay-ms=3600000", "app.optional-work.enabled=true",
    "app.optional-work.retry-attempts=3", "app.optional-work.retry-delay-ms=50",
    "pedidos360.messaging.dlq-monitor-enabled=true"})
class RealRabbitPipelineIT {
    private static final String RUN = UUID.randomUUID().toString().substring(0, 12);
    private static final String PREFIX = "pedidos360.it." + RUN;
    private static final String DIRECT = PREFIX + ".direct";
    private static final String TOPIC = PREFIX + ".topic";
    private static final String NOTIFICATION = PREFIX + ".notification";
    private static final String TICKET = PREFIX + ".ticket";

    @Autowired OrderOutboxRepository outbox;
    @Autowired OrderEventPublisher publisher;
    @Autowired OrderNotificationRepository notifications;
    @Autowired OrderTicketRepository tickets;
    @Autowired RejectedRabbitMessageRepository rejected;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin rabbitAdmin;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @MockitoBean JwtDecoder decoder;
    @MockitoSpyBean OrderWorkService work;

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", () -> System.getenv().getOrDefault("RABBITMQ_HOST", "localhost"));
        registry.add("spring.rabbitmq.port", () -> Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672")));
        registry.add("spring.rabbitmq.username", () -> System.getenv().getOrDefault("RABBITMQ_USERNAME", "pedidos360"));
        registry.add("spring.rabbitmq.password", () -> System.getenv().getOrDefault("RABBITMQ_PASSWORD", "pedidos360-local"));
        registry.add("spring.rabbitmq.virtual-host", () -> System.getenv().getOrDefault("RABBITMQ_VHOST", "/pedidos360"));
        registry.add("pedidos360.messaging.exchanges.orders-direct", () -> DIRECT);
        registry.add("pedidos360.messaging.exchanges.orders-topic", () -> TOPIC);
        registry.add("pedidos360.messaging.exchanges.dead-letter", () -> PREFIX + ".dead-letter");
        registry.add("pedidos360.messaging.queues.notification", () -> NOTIFICATION);
        registry.add("pedidos360.messaging.queues.notification-dlq", () -> NOTIFICATION + ".dlq");
        registry.add("pedidos360.messaging.queues.ticket", () -> TICKET);
        registry.add("pedidos360.messaging.queues.ticket-dlq", () -> TICKET + ".dlq");
        registry.add("pedidos360.messaging.queues.audit", () -> PREFIX + ".audit");
        registry.add("pedidos360.messaging.queues.audit-dlq", () -> PREFIX + ".audit.dlq");
    }

    @AfterAll void cleanup() {
        listeners.stop();
        for (String queue : List.of(NOTIFICATION, NOTIFICATION + ".dlq", TICKET, TICKET + ".dlq",
                PREFIX + ".audit", PREFIX + ".audit.dlq")) rabbitAdmin.deleteQueue(queue);
        for (String exchange : List.of(DIRECT, TOPIC, PREFIX + ".dead-letter"))
            rabbitAdmin.deleteExchange(exchange);
    }

    @Test void productorBrokerConsumidorPersistenciaReintentoDuplicadoYDlq() throws Exception {
        String eventId = "rabbit-created-" + RUN;
        String payload = event(eventId, 910001);
        OrderOutboxMessage notification = outbox.saveAndFlush(
            new OrderOutboxMessage(eventId, DIRECT, "orders.created", payload));
        OrderOutboxMessage ticket = outbox.saveAndFlush(
            new OrderOutboxMessage(eventId, TOPIC, "orders.created.ticket", payload));
        publisher.dispatchPending();
        await(() -> notifications.existsByEventId(eventId) && tickets.existsByEventId(eventId), 30);
        assertNotNull(outbox.findById(notification.getId()).orElseThrow().getPublishedAt());
        assertNotNull(outbox.findById(ticket.getId()).orElseThrow().getPublishedAt());

        rabbit.convertAndSend(DIRECT, "orders.created", payload);
        rabbit.convertAndSend(TOPIC, "orders.created.ticket", payload);
        verify(work, timeout(10000).times(2)).notifyCustomer(
            argThat(value -> value != null && eventId.equals(value.eventId())));
        verify(work, timeout(10000).times(2)).issueTicket(
            argThat(value -> value != null && eventId.equals(value.eventId())));
        assertEquals(1, notifications.count());
        assertEquals(1, tickets.count());

        AtomicInteger attempts = new AtomicInteger();
        String retryId = "rabbit-retry-" + RUN;
        doAnswer(call -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("fallo transitorio de prueba");
            return call.callRealMethod();
        }).when(work).notifyCustomer(argThat(value -> value != null && retryId.equals(value.eventId())));
        rabbit.convertAndSend(DIRECT, "orders.created", event(retryId, 910002));
        await(() -> notifications.existsByEventId(retryId), 30);
        assertEquals(2, attempts.get());

        rabbit.convertAndSend(DIRECT, "orders.created", "{invalid");
        await(() -> rejected.findAll().stream().anyMatch(row ->
            (NOTIFICATION + ".dlq").equals(row.getDlqName())
                && NOTIFICATION.equals(row.getSourceQueue())
                && "rejected".equals(row.getDeathReason())), 30);
        assertEquals(2, notifications.count());
    }

    private static String event(String id, long orderId) {
        String now = Instant.now().toString();
        return """
            {"schemaVersion":2,"eventId":"%s","eventType":"ORDER_CREATED","orderId":%d,
             "customerName":"Cliente","customerId":"oid-it","customerTenantId":"tid-it",
             "total":200,"createdAt":"%s","occurredAt":"%s",
             "items":[{"productId":"7","name":"Producto","quantity":2,"unitPrice":100}]}
            """.formatted(id, orderId, now, now);
    }

    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("El consumidor no confirmó el evento en " + seconds + " s");
    }
}
