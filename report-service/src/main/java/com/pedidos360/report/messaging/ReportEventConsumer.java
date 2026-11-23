package com.pedidos360.report.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class ReportEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(ReportEventConsumer.class);
    private final ReportProjectionWriter writer;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final String dltTopic;
    public ReportEventConsumer(ReportProjectionWriter writer, ObjectMapper mapper, KafkaTemplate<String, String> kafka,
                               @Value("${app.report.dlt-topic:pedidos360.report.dlt.v1}") String dltTopic) {
        this.writer = writer; this.mapper = mapper; this.kafka = kafka; this.dltTopic = dltTopic;
    }
    @KafkaListener(topics = "${app.report.topic:pedidos360.orders.events.v1}",
                   groupId = "${spring.kafka.consumer.group-id:pedidos360-report}",
                   autoStartup = "${app.report.kafka-enabled:false}", ackMode = "MANUAL_IMMEDIATE")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        try {
            writer.apply(ReportOrderEvent.parse(mapper, record.value()));
            ack.acknowledge();
        } catch (ReportOrderEvent.InvalidEvent invalid) {
            DeadLetterPublisher.publish(kafka, dltTopic, record, invalid.getMessage());
            log.warn("Evento de reportes inválido en {} offset {}: {}", record.topic(), record.offset(), invalid.getMessage());
            ack.acknowledge();
        }
    }
}
