package com.pedidos360.audit.messaging;

import com.pedidos360.audit.model.RejectedKafkaEvent;
import com.pedidos360.audit.repository.RejectedKafkaEventRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class DeadLetterMonitor {
    private final RejectedKafkaEventRepository repository;
    public DeadLetterMonitor(RejectedKafkaEventRepository repository) { this.repository = repository; }

    @KafkaListener(topics = {"${app.audit.dlt-topic:pedidos360.audit.dlt.v1}",
                            "${app.report.dlt-topic:pedidos360.report.dlt.v1}"},
                   groupId = "${app.audit.dlt-monitor-group:pedidos360-dlt-monitor}",
                   autoStartup = "${app.audit.dlt-monitor-enabled:false}", ackMode = "MANUAL_IMMEDIATE")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        if (!repository.existsByDltTopicAndDltPartitionAndDltOffset(record.topic(), record.partition(), record.offset())) {
            repository.saveAndFlush(new RejectedKafkaEvent(record.topic(), record.partition(), record.offset(),
                bounded(header(record, "source-topic"), 255), integer(header(record, "source-partition")),
                longValue(header(record, "source-offset")), bounded(header(record, "cause"), 500),
                date(header(record, "rejected-at")), OffsetDateTime.now(ZoneOffset.UTC), hash(record.value())));
        }
        acknowledgment.acknowledge();
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(DeadLetterPublisher.PREFIX + name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
    private String bounded(String value, int max) { return value == null ? null : value.substring(0, Math.min(value.length(), max)); }
    private Integer integer(String value) {
        try { return value == null ? null : Integer.valueOf(value); }
        catch (NumberFormatException ex) { return null; }
    }
    private Long longValue(String value) {
        try { return value == null ? null : Long.valueOf(value); }
        catch (NumberFormatException ex) { return null; }
    }
    private OffsetDateTime date(String value) {
        try { return value == null ? null : OffsetDateTime.parse(value); }
        catch (DateTimeParseException ex) { return null; }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
