package com.pedidos360.pedidos.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OptionalOrderWorkConsumers {
    private static final Logger log = LoggerFactory.getLogger(OptionalOrderWorkConsumers.class);
    private final ObjectMapper mapper;
    private final OrderWorkService service;
    private final int attempts;
    private final long delayMs;
    public OptionalOrderWorkConsumers(ObjectMapper mapper, OrderWorkService service,
            @Value("${app.optional-work.retry-attempts:3}") int attempts,
            @Value("${app.optional-work.retry-delay-ms:500}") long delayMs) {
        this.mapper = mapper; this.service = service;
        this.attempts = Math.max(1, attempts); this.delayMs = Math.max(0, delayMs);
    }

    @RabbitListener(queues = "${pedidos360.messaging.queues.notification}",
                    autoStartup = "${app.optional-work.enabled:true}")
    public void notifyCustomer(Message message, Channel channel) throws IOException {
        process("notification", message, channel, service::notifyCustomer);
    }

    @RabbitListener(queues = "${pedidos360.messaging.queues.ticket}",
                    autoStartup = "${app.optional-work.enabled:true}")
    public void issueTicket(Message message, Channel channel) throws IOException {
        process("ticket", message, channel, service::issueTicket);
    }

    private void process(String task, Message message, Channel channel, Consumer<OrderWorkEvent> work) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        OrderWorkEvent event;
        try { event = OrderWorkEvent.parse(mapper, message.getBody()); }
        catch (OrderWorkEvent.InvalidWorkEventException invalid) {
            log.warn("Evento no verificable en {} messageId={}; enviado a DLQ", task,
                message.getMessageProperties().getMessageId());
            channel.basicNack(tag, false, false);
            return;
        }
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                work.accept(event);
                channel.basicAck(tag, false);
                return;
            } catch (RuntimeException failure) {
                if (attempt == attempts) {
                    log.error("Falló {} tras {} intentos, eventId={}, causa={}; enviado a DLQ",
                        task, attempt, event.eventId(), failure.getClass().getSimpleName());
                    channel.basicNack(tag, false, false);
                    return;
                }
                try { Thread.sleep(delayMs); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    channel.basicNack(tag, false, false);
                    return;
                }
            }
        }
    }
}
