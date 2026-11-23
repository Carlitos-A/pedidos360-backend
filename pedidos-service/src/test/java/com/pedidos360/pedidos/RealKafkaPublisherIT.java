package com.pedidos360.pedidos;

import com.pedidos360.pedidos.messaging.OrderEventPublisher;
import com.pedidos360.pedidos.model.OrderOutboxMessage;
import com.pedidos360.pedidos.repository.OrderOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RUN_BROKER_IT", matches = "true")
@SpringBootTest(properties = {"spring.rabbitmq.listener.simple.auto-startup=false",
    "app.outbox.initial-delay-ms=3600000", "app.optional-work.enabled=false"})
class RealKafkaPublisherIT {
    private static final String BOOTSTRAP = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS",
        "localhost:9092,localhost:9093,localhost:9094");
    private static final String RUN = UUID.randomUUID().toString().substring(0, 12);
    private static final String TOPIC = "pedidos360.it.publisher." + RUN;
    @Autowired OrderOutboxRepository outbox;
    @Autowired OrderEventPublisher publisher;
    @MockitoBean JwtDecoder decoder;

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", BOOTSTRAP))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 3))).all().get(30, TimeUnit.SECONDS);
        }
        registry.add("spring.kafka.bootstrap-servers", () -> BOOTSTRAP);
    }

    @AfterAll static void cleanup() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", BOOTSTRAP))) {
            admin.deleteTopics(List.of(TOPIC)).all().get(30, TimeUnit.SECONDS);
        }
    }

    @Test void outboxDePedidosPublicaSnapshotConOrderIdYConfirma() throws Exception {
        String eventId = "snapshot-" + RUN;
        String payload = """
            {"schemaVersion":1,"eventId":"%s","type":"ORDER_SNAPSHOT","orderId":"42",
             "revision":1,"occurredAt":"%s","createdAt":"%s","status":"CREATED",
             "total":200,"items":[{"productId":"7","name":"Producto","quantity":2,"unitPrice":100}]}
            """.formatted(eventId, Instant.now(), Instant.now());
        OrderOutboxMessage message = outbox.saveAndFlush(new OrderOutboxMessage(eventId, "kafka", TOPIC, payload));
        try (KafkaConsumer<String, String> observer = observer()) {
            observer.subscribe(List.of(TOPIC));
            publisher.dispatchPending();
            assertNotNull(outbox.findById(message.getId()).orElseThrow().getPublishedAt());
            boolean found = false;
            Instant deadline = Instant.now().plusSeconds(20);
            while (Instant.now().isBefore(deadline) && !found) {
                for (var record : observer.poll(Duration.ofMillis(500))) {
                    if (!payload.equals(record.value())) continue;
                    assertEquals("order:42", record.key());
                    found = true;
                }
            }
            assertTrue(found, "El snapshot no llegó al broker Kafka");
        }
    }

    private KafkaConsumer<String, String> observer() {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "pedidos360-it-publisher-observer-" + RUN);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(config);
    }
}
