package com.pedidos360.audit.login;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"app.entra.signins-enabled", "app.audit.kafka-enabled"}, havingValue = "true")
public class EntraSignInProjector {
    private static final String CURSOR_ID = "entra-interactive-signins";
    private final GraphSignInClient graph;
    private final EntraSignInCursorRepository cursors;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final String tenantId;
    private final String topic;
    private final long initialLookbackHours;
    private final long overlapHours;

    public EntraSignInProjector(GraphSignInClient graph, EntraSignInCursorRepository cursors,
                                KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                                @Value("${app.entra.tenant-id:}") String tenantId,
                                @Value("${app.audit.login-topic:pedidos360.login.v1}") String topic,
                                @Value("${app.entra.initial-lookback-hours:24}") long initialLookbackHours,
                                @Value("${app.entra.overlap-hours:24}") long overlapHours) {
        if (tenantId.isBlank() || initialLookbackHours < 1 || overlapHours < 1)
            throw new IllegalArgumentException("Configuración de Entra inválida");
        this.graph = graph; this.cursors = cursors; this.kafka = kafka; this.mapper = mapper;
        this.tenantId = tenantId; this.topic = topic;
        this.initialLookbackHours = initialLookbackHours; this.overlapHours = overlapHours;
    }

    @Scheduled(fixedDelayString = "${app.entra.poll-ms:60000}",
               initialDelayString = "${app.entra.initial-delay-ms:15000}")
    public void poll() throws Exception {
        Instant end = Instant.now();
        EntraSignInCursor cursor = cursors.findById(CURSOR_ID).orElse(null);
        Instant from = cursor == null ? end.minus(initialLookbackHours, ChronoUnit.HOURS)
            : cursor.getLastCompletedAt().minus(overlapHours, ChronoUnit.HOURS);
        for (GraphSignInClient.SignIn signIn : graph.list(from, end)) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("schemaVersion", 1);
            event.put("eventId", "entra-signin:" + signIn.id());
            event.put("type", "USER_LOGIN");
            event.put("entityId", signIn.id());
            event.put("occurredAt", signIn.occurredAt().toString());
            event.put("actorId", signIn.userId());
            event.put("actorTenantId", tenantId);
            event.put("actorEmail", signIn.email());
            kafka.send(topic, "user:" + tenantId + ":" + signIn.userId(), mapper.writeValueAsString(event))
                .get(10, TimeUnit.SECONDS);
        }
        cursors.save(new EntraSignInCursor(CURSOR_ID, end));
    }
}
