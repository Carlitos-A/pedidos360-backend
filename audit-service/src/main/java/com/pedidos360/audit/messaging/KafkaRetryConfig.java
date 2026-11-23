package com.pedidos360.audit.messaging;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaRetryConfig {
    @Bean
    CommonErrorHandler auditErrorHandler(@Value("${app.audit.retry-delay-ms:1000}") long delayMs) {
        // Invalid payloads go to DLT once. Database/DLT failures retry this offset until recovered.
        // An exhausted finite retry would otherwise let DefaultErrorHandler skip an audit record.
        return new DefaultErrorHandler(new FixedBackOff(delayMs, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
