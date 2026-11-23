package com.pedidos360.audit;

import com.pedidos360.audit.repository.AuditEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RUN_BROKER_IT", matches = "true")
@SpringBootTest
class RealKafkaAuditPipelineIT {
    private static final String BOOTSTRAP = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS",
        "localhost:9092,localhost:9093,localhost:9094");
    private static final String RUN = UUID.randomUUID().toString().substring(0, 12);
    private static final String SOURCE = "pedidos360.it.audit." + RUN;
    private static final String LOGIN = "pedidos360.it.login." + RUN;
    private static final String DLT = "pedidos360.it.audit.dlt." + RUN;
    private static final String GROUP = "pedidos360-it-audit-" + RUN;
    @Autowired KafkaTemplate<String, String> producer;
    @Autowired AuditEventRepository events;
    @MockitoBean JwtDecoder decoder;

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) throws Exception {
        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(topic(SOURCE), topic(LOGIN), topic(DLT)))
                .all().get(30, TimeUnit.SECONDS);
        }
        registry.add("spring.kafka.bootstrap-servers", () -> BOOTSTRAP);
        registry.add("app.audit.kafka-enabled", () -> true);
        registry.add("app.audit.topic", () -> SOURCE);
        registry.add("app.audit.login-topic", () -> LOGIN);
        registry.add("app.audit.dlt-topic", () -> DLT);
        registry.add("app.audit.group-id", () -> GROUP);
        registry.add("app.audit.retry-delay-ms", () -> 200);
    }

    @AfterAll static void cleanup() throws Exception {
        try (AdminClient admin = admin()) {
            admin.deleteTopics(List.of(SOURCE, LOGIN, DLT)).all().get(30, TimeUnit.SECONDS);
        }
    }

    @Test void brokerPersisteAuditoriaDeduplicaYEntregaInvalidoADlt() throws Exception {
        String eventId = "audit-created-" + RUN;
        String payload = """
            {"schemaVersion":1,"eventId":"%s","type":"ORDER_CREATED","entityId":"42",
             "occurredAt":"%s","status":"CREATED","actorEmail":"admin@example.test"}
            """.formatted(eventId, Instant.now());
        producer.send(SOURCE, "order:42", payload).get(10, TimeUnit.SECONDS);
        await(() -> events.existsByEventId(eventId), 25);
        assertEquals(1, events.count());
        var duplicate = producer.send(SOURCE, "order:42", payload).get(10, TimeUnit.SECONDS)
            .getRecordMetadata();
        await(() -> committed(duplicate.partition()) > duplicate.offset(), 25);
        assertEquals(1, events.count());
        var bad = producer.send(SOURCE, "order:42", "{invalid").get(10, TimeUnit.SECONDS)
            .getRecordMetadata();
        try (KafkaConsumer<String, String> observer = observer()) {
            observer.subscribe(List.of(DLT));
            boolean found = false;
            Instant deadline = Instant.now().plusSeconds(25);
            while (Instant.now().isBefore(deadline) && !found) {
                for (var record : observer.poll(Duration.ofMillis(500))) {
                    if (!"{invalid".equals(record.value())) continue;
                    assertEquals(SOURCE, header(record, "source-topic"));
                    assertEquals(Integer.toString(bad.partition()), header(record, "source-partition"));
                    assertEquals(Long.toString(bad.offset()), header(record, "source-offset"));
                    assertFalse(header(record, "cause").isBlank());
                    Instant.parse(header(record, "rejected-at"));
                    found = true;
                }
            }
            assertTrue(found, "El rechazo de auditoría no llegó a Kafka DLT");
        }
        assertEquals(1, events.count());
    }

    private static NewTopic topic(String name) {
        return new NewTopic(name, 3, (short) 3).configs(Map.of("cleanup.policy", "delete"));
    }
    private static AdminClient admin() { return AdminClient.create(Map.of("bootstrap.servers", BOOTSTRAP)); }
    private long committed(int partition) {
        try (AdminClient admin = admin()) {
            var value = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS).get(new TopicPartition(SOURCE, partition));
            return value == null ? -1 : value.offset();
        } catch (Exception unavailable) { return -1; }
    }
    private static KafkaConsumer<String, String> observer() {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "pedidos360-it-audit-observer-" + RUN);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(config);
    }
    private String header(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record, String key) {
        var header = record.headers().lastHeader("pedidos360.dlt." + key);
        assertNotNull(header, key);
        return new String(header.value(), StandardCharsets.UTF_8);
    }
    private void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("La auditoría no persistió el evento dentro de " + seconds + " s");
    }
}
