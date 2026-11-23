package com.pedidos360.report;

import com.pedidos360.report.repository.ReportOrderRepository;
import com.pedidos360.report.repository.ReportReceiptRepository;
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
import org.apache.kafka.common.config.ConfigResource;
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
class RealKafkaPipelineIT {
    private static final String BOOTSTRAP = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS",
        "localhost:9092,localhost:9093,localhost:9094");
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String SOURCE = "pedidos360.it.orders." + RUN;
    private static final String DLT = "pedidos360.it.report.dlt." + RUN;
    private static final String GROUP = "pedidos360-it-report-" + RUN;

    @Autowired KafkaTemplate<String, String> producer;
    @Autowired ReportOrderRepository orders;
    @Autowired ReportReceiptRepository receipts;
    @MockitoBean JwtDecoder decoder;

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) throws Exception {
        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(topic(SOURCE))).all().get(30, TimeUnit.SECONDS);
        }
        registry.add("spring.kafka.bootstrap-servers", () -> BOOTSTRAP);
        registry.add("spring.kafka.consumer.group-id", () -> GROUP);
        registry.add("app.report.group-id", () -> GROUP);
        registry.add("app.report.kafka-enabled", () -> true);
        registry.add("app.report.topic", () -> SOURCE);
        registry.add("app.report.dlt-topic", () -> DLT);
        registry.add("app.report.retry-delay-ms", () -> 200);
    }

    @AfterAll static void cleanup() throws Exception {
        try (AdminClient admin = admin()) {
            admin.deleteTopics(List.of(SOURCE, DLT)).all().get(30, TimeUnit.SECONDS);
        }
    }

    @Test void brokerProyectaDeduplicaReintentaDltYConfirmaTopologia() throws Exception {
        try (AdminClient admin = admin()) {
            var description = admin.describeTopics(List.of(SOURCE)).allTopicNames()
                .get(10, TimeUnit.SECONDS).get(SOURCE);
            assertEquals(3, description.partitions().size());
            assertTrue(description.partitions().stream().allMatch(p -> p.replicas().size() == 3));
            var config = admin.describeConfigs(List.of(new ConfigResource(ConfigResource.Type.TOPIC, SOURCE)))
                .all().get(10, TimeUnit.SECONDS).values().iterator().next();
            assertEquals("delete", config.get("cleanup.policy").value());
        }

        Instant created = Instant.now().minusSeconds(120);
        long orderId = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L) + 1;
        String first = snapshot("created-" + RUN, orderId, 1, "CREATED", created, created);
        var sentFirst = producer.send(SOURCE, "order:" + orderId, first).get(10, TimeUnit.SECONDS);
        await(() -> receipts.existsById("created-" + RUN), 25);
        assertEquals("CREATED", orders.findById(orderId).orElseThrow().getStatus());

        String completed = snapshot("completed-" + RUN, orderId, 2, "COMPLETED", created, Instant.now());
        producer.send(SOURCE, "order:" + orderId, completed).get(10, TimeUnit.SECONDS);
        await(() -> "COMPLETED".equals(orders.findById(orderId).orElseThrow().getStatus()), 25);
        var duplicate = producer.send(SOURCE, "order:" + orderId, completed).get(10, TimeUnit.SECONDS);
        await(() -> committed(duplicate.getRecordMetadata().partition()) > duplicate.getRecordMetadata().offset(), 25);
        assertEquals(2, receipts.count());
        assertEquals(200L, orders.findById(orderId).orElseThrow().getTotal());

        var invalid = producer.send(SOURCE, "order:" + orderId, "{invalid")
            .get(10, TimeUnit.SECONDS).getRecordMetadata();
        // DLT does not exist yet. The source offset must remain uncommitted while publication retries.
        Thread.sleep(6000);
        assertTrue(committed(invalid.partition()) <= invalid.offset());
        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(topic(DLT))).all().get(30, TimeUnit.SECONDS);
        }
        try (KafkaConsumer<String, String> observer = dltConsumer()) {
            observer.subscribe(List.of(DLT));
            var deadline = Instant.now().plusSeconds(30);
            boolean found = false;
            while (Instant.now().isBefore(deadline) && !found) {
                for (var record : observer.poll(Duration.ofMillis(500))) {
                    if (!"{invalid".equals(record.value())) continue;
                    assertEquals(SOURCE, header(record, "source-topic"));
                    assertEquals(Integer.toString(invalid.partition()), header(record, "source-partition"));
                    assertEquals(Long.toString(invalid.offset()), header(record, "source-offset"));
                    assertFalse(header(record, "cause").isBlank());
                    Instant.parse(header(record, "rejected-at"));
                    found = true;
                }
            }
            assertTrue(found, "El evento inválido no llegó a la DLT real");
        }
        await(() -> committed(invalid.partition()) > invalid.offset(), 25);
        assertEquals(2, receipts.count());
        assertEquals(sentFirst.getRecordMetadata().partition(), invalid.partition());
    }

    private static NewTopic topic(String name) {
        return new NewTopic(name, 3, (short) 3).configs(Map.of(
            "retention.ms", "600000", "cleanup.policy", "delete", "min.insync.replicas", "2"));
    }

    private static AdminClient admin() { return AdminClient.create(Map.of("bootstrap.servers", BOOTSTRAP)); }

    private long committed(int partition) {
        try (AdminClient admin = admin()) {
            var offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS).get(new TopicPartition(SOURCE, partition));
            return offset == null ? -1 : offset.offset();
        } catch (Exception ex) { return -1; }
    }

    private KafkaConsumer<String, String> dltConsumer() {
        Properties settings = new Properties();
        settings.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        settings.put(ConsumerConfig.GROUP_ID_CONFIG, "pedidos360-it-observer-" + RUN);
        settings.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        settings.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        settings.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(settings);
    }

    private String header(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record, String key) {
        var value = record.headers().lastHeader("pedidos360.dlt." + key);
        assertNotNull(value, key);
        return new String(value.value(), StandardCharsets.UTF_8);
    }

    private String snapshot(String eventId, long orderId, int revision, String status,
                            Instant created, Instant occurred) {
        return """
            {"schemaVersion":1,"eventId":"%s","type":"ORDER_SNAPSHOT","orderId":"%d",
             "revision":%d,"occurredAt":"%s","createdAt":"%s","status":"%s","total":200,
             "items":[{"productId":"7","name":"Producto","quantity":2,"unitPrice":100}]}
            """.formatted(eventId, orderId, revision, occurred, created, status);
    }

    private void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("La proyección o el offset no avanzó dentro de " + seconds + " s");
    }
}
