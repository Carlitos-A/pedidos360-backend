package com.pedidos360.report.messaging;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;

final class DeadLetterPublisher {
    static final String PREFIX = "pedidos360.dlt.";
    private DeadLetterPublisher() {}

    static void publish(KafkaTemplate<String, String> kafka, String dltTopic,
                        ConsumerRecord<String, String> source, String cause) throws Exception {
        ProducerRecord<String, String> rejected = new ProducerRecord<>(dltTopic, source.key(), source.value());
        header(rejected, "source-topic", source.topic());
        header(rejected, "source-partition", Integer.toString(source.partition()));
        header(rejected, "source-offset", Long.toString(source.offset()));
        header(rejected, "cause", cause);
        header(rejected, "rejected-at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        kafka.send(rejected).get(10, TimeUnit.SECONDS);
    }

    private static void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(new RecordHeader(PREFIX + name, value.getBytes(StandardCharsets.UTF_8)));
    }
}
