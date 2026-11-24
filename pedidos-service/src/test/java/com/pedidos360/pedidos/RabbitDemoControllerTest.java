package com.pedidos360.pedidos;

import com.pedidos360.pedidos.controller.RabbitDemoController;
import com.pedidos360.pedidos.messaging.RabbitDemoTopology;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RabbitDemoControllerTest {
    @Test void publicaSoloElPayloadInvalidoFijoEnElExchangeDePrueba() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doAnswer(call -> {
            Message message = call.getArgument(2);
            assertEquals("{invalid", new String(message.getBody(), StandardCharsets.UTF_8));
            assertTrue(message.getMessageProperties().getMessageId().startsWith("rabbit-demo-"));
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(eq(RabbitDemoTopology.EXCHANGE), eq(RabbitDemoTopology.ROUTING_KEY),
            any(Message.class), any(CorrelationData.class));

        var result = new RabbitDemoController(rabbit).run(new RabbitDemoController.Request("reject"));
        assertEquals("reject", result.action());
        assertEquals(RabbitDemoTopology.DLQ, result.queue());
        assertTrue(result.messageId().startsWith("rabbit-demo-"));
        verify(rabbit, times(1)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test void rechazaAccionesDesconocidasSinPublicar() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        var error = assertThrows(ResponseStatusException.class,
            () -> new RabbitDemoController(rabbit).run(new RabbitDemoController.Request("otro")));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(rabbit);
    }
}
