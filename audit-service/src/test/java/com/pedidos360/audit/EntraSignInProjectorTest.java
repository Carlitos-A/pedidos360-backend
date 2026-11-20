package com.pedidos360.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.audit.login.EntraSignInCursor;
import com.pedidos360.audit.login.EntraSignInCursorRepository;
import com.pedidos360.audit.login.EntraSignInProjector;
import com.pedidos360.audit.login.GraphSignInClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EntraSignInProjectorTest {
    @Test void publicaLoginVerificadoYAvanzaCursorDespuesDeConfirmar() throws Exception {
        GraphSignInClient graph = mock(GraphSignInClient.class);
        EntraSignInCursorRepository cursors = mock(EntraSignInCursorRepository.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var projector = new EntraSignInProjector(graph, cursors, kafka, new ObjectMapper(),
            "tenant-1", "pedidos360.login.v1", 24, 24);
        when(cursors.findById("entra-interactive-signins")).thenReturn(Optional.empty());
        when(graph.list(any(), any())).thenReturn(List.of(new GraphSignInClient.SignIn(
            "signin-1", "user-1", "user@example.test", Instant.parse("2026-11-20T12:00:00Z"))));
        when(kafka.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
        projector.poll();
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("pedidos360.login.v1"), eq("user:tenant-1:user-1"), payload.capture());
        var event = new ObjectMapper().readTree(payload.getValue());
        assertEquals("entra-signin:signin-1", event.path("eventId").asText());
        assertEquals("USER_LOGIN", event.path("type").asText());
        assertEquals("user@example.test", event.path("actorEmail").asText());
        verify(cursors).save(any(EntraSignInCursor.class));
    }

    @Test void falloKafkaNoAvanzaCursor() throws Exception {
        GraphSignInClient graph = mock(GraphSignInClient.class);
        EntraSignInCursorRepository cursors = mock(EntraSignInCursorRepository.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        var projector = new EntraSignInProjector(graph, cursors, kafka, new ObjectMapper(),
            "tenant-1", "pedidos360.login.v1", 24, 24);
        when(cursors.findById(anyString())).thenReturn(Optional.empty());
        when(graph.list(any(), any())).thenReturn(List.of(new GraphSignInClient.SignIn(
            "signin-1", "user-1", "user@example.test", Instant.parse("2026-11-20T12:00:00Z"))));
        when(kafka.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        assertThrows(Exception.class, projector::poll);
        verify(cursors, never()).save(any());
    }
}
