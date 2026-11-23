package com.pedidos360.pedidos.messaging;

import com.pedidos360.pedidos.model.RejectedRabbitMessage;
import com.pedidos360.pedidos.repository.RejectedRabbitMessageRepository;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class RabbitDeadLetterMonitor {
    private static final Logger log = LoggerFactory.getLogger(RabbitDeadLetterMonitor.class);
    private final RejectedRabbitMessageRepository repository;
    public RabbitDeadLetterMonitor(RejectedRabbitMessageRepository repository) { this.repository = repository; }

    @RabbitListener(queues = "${pedidos360.messaging.queues.notification-dlq}",
                    autoStartup = "${pedidos360.messaging.dlq-monitor-enabled:false}")
    public void notification(Message message, Channel channel) throws IOException {
        record(message, channel, message.getMessageProperties().getConsumerQueue());
    }
    @RabbitListener(queues = "${pedidos360.messaging.queues.ticket-dlq}",
                    autoStartup = "${pedidos360.messaging.dlq-monitor-enabled:false}")
    public void ticket(Message message, Channel channel) throws IOException {
        record(message, channel, message.getMessageProperties().getConsumerQueue());
    }
    @RabbitListener(queues = "${pedidos360.messaging.queues.audit-dlq}",
                    autoStartup = "${pedidos360.messaging.dlq-monitor-enabled:false}")
    public void audit(Message message, Channel channel) throws IOException {
        record(message, channel, message.getMessageProperties().getConsumerQueue());
    }

    public void record(Message message, Channel channel, String dlqName) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try { store(message, dlqName); }
        catch (RuntimeException failure) {
            log.error("No se pudo registrar rechazo de DLQ={} messageId={}; se reencola",
                dlqName, message.getMessageProperties().getMessageId(), failure);
            try { Thread.sleep(1000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            channel.basicNack(tag, false, true);
            return;
        }
        channel.basicAck(tag, false);
    }

    private void store(Message message, String dlqName) {
        if (dlqName == null || dlqName.isBlank()) throw new IllegalArgumentException("DLQ desconocida");
        Map<String, Object> headers = message.getMessageProperties().getHeaders();
        Map<?, ?> death = firstDeath(headers.get("x-death"));
        String sourceQueue = header(headers, death, "x-first-death-queue", "queue");
        String reason = header(headers, death, "x-first-death-reason", "reason");
        String messageId = message.getMessageProperties().getMessageId();
        String bodyHash = hash(message.getBody());
        String deathTime = String.valueOf(death.containsKey("time") ? death.get("time") : "");
        String fingerprint = hash((dlqName + "|" + (messageId == null || messageId.isBlank()
            ? bodyHash : messageId) + "|" + deathTime).getBytes(StandardCharsets.UTF_8));
        if (!repository.existsByFingerprint(fingerprint)) {
            repository.saveAndFlush(new RejectedRabbitMessage(fingerprint, bounded(dlqName, 255),
                bounded(sourceQueue, 255), bounded(reason, 100), bounded(messageId, 255), bodyHash,
                OffsetDateTime.now(ZoneOffset.UTC)));
        }
    }

    private Map<?, ?> firstDeath(Object value) {
        if (value instanceof List<?> entries && !entries.isEmpty() && entries.getFirst() instanceof Map<?, ?> first)
            return first;
        return Map.of();
    }
    private String header(Map<String, Object> headers, Map<?, ?> death, String key, String fallback) {
        Object value = headers.getOrDefault(key, death.get(fallback));
        return value == null ? null : value.toString();
    }
    private String bounded(String value, int max) { return value == null ? null : value.substring(0, Math.min(max, value.length())); }
    private String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
