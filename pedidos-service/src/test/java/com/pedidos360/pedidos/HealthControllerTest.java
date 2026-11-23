package com.pedidos360.pedidos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedidos360.pedidos.controller.HealthController;
import com.pedidos360.pedidos.controller.HealthController.Entry;
import com.pedidos360.pedidos.repository.PedidoRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private HttpServer server;
    private PedidoRepository orders;

    private record Reply(int code, String body) {}

    @BeforeEach void start() throws Exception {
        orders = mock(PedidoRepository.class);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/health", exchange -> {
            String name = exchange.getRequestURI().getQuery().replace("service=", "");
            Reply reply = "test-key".equals(exchange.getRequestHeaders().getFirst("X-Internal-Key"))
                ? replies.getOrDefault(name, new Reply(503, "")) : new Reply(403, "");
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.code(), body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void informaUpSoloConCheckInternoDeLaIdentidadEsperada() {
        when(orders.count()).thenReturn(2L);
        for (String name : List.of("bff", "products", "report", "audit"))
            replies.put(name, new Reply(200, "{\"service\":\"" + name + "\",\"status\":\"UP\"}"));

        var result = controller(url("bff"), url("products"), url("report"), url("audit"), "test-key").check();
        assertEquals(List.of(new Entry("orders", "UP"), new Entry("bff", "UP"),
            new Entry("products", "UP"), new Entry("report", "UP"), new Entry("audit", "UP")),
            result.services());
    }

    @Test void noInfiereSaludDe401NiDeOtroServicioYOmiteLosNoConfigurados() {
        when(orders.count()).thenThrow(new IllegalStateException("Base caída"));
        replies.put("products", new Reply(401, ""));
        replies.put("report", new Reply(200, "{\"service\":\"orders\",\"status\":\"UP\"}"));

        var result = controller("", url("products"), url("report"), "", "test-key").check();
        assertEquals(List.of(new Entry("orders", "DOWN"), new Entry("products", "DOWN"),
            new Entry("report", "DOWN")), result.services());
    }

    @Test void sinClaveInternaNoInventaEstadoRemoto() {
        var result = controller(url("bff"), url("products"), url("report"), url("audit"), "").check();
        assertEquals(List.of(new Entry("orders", "UP")), result.services());
    }

    private HealthController controller(String bff, String products, String report, String audit, String key) {
        return new HealthController(orders, new ObjectMapper(), bff, products, audit, report, key);
    }

    private String url(String name) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/health?service=" + name;
    }
}
