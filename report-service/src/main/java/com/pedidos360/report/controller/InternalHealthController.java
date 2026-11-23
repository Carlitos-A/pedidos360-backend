package com.pedidos360.report.controller;

import com.pedidos360.report.repository.ReportOrderRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InternalHealthController {
    private final ReportOrderRepository orders;
    private final KafkaTemplate<String, String> kafka;
    private final String key;
    private final boolean kafkaEnabled;
    private final String topic;
    public InternalHealthController(ReportOrderRepository orders, KafkaTemplate<String, String> kafka,
                                    @Value("${app.internal.key:}") String key,
                                    @Value("${app.report.kafka-enabled:false}") boolean kafkaEnabled,
                                    @Value("${app.report.topic:pedidos360.orders.events.v1}") String topic) {
        this.orders = orders; this.kafka = kafka; this.key = key;
        this.kafkaEnabled = kafkaEnabled; this.topic = topic;
    }
    @GetMapping("/internal/health")
    public Map<String, String> check(@RequestHeader(value = "X-Internal-Key", required = false) String supplied) {
        if (key.isBlank() || supplied == null || !MessageDigest.isEqual(
            key.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        try {
            orders.count();
            if (kafkaEnabled && kafka.partitionsFor(topic).isEmpty())
                throw new IllegalStateException("Sin particiones Kafka");
            return Map.of("service", "report", "status", "UP");
        }
        catch (Exception ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE); }
    }
}
