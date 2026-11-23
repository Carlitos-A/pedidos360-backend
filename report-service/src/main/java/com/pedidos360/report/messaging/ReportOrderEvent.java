package com.pedidos360.report.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Minimal, versioned business snapshot. Values come from the confirmed order, not the browser. */
public record ReportOrderEvent(String eventId, long orderId, long revision, Instant occurredAt,
                               Instant createdAt, String status, long total, List<Line> items) {
    public record Line(String productId, String name, int quantity, long unitPrice) {}
    private static final Set<String> STATUSES = Set.of("CREATED", "ACCEPTED", "IN_PROGRESS", "COMPLETED", "CANCELLED");

    public static ReportOrderEvent parse(ObjectMapper mapper, String payload) {
        try {
            JsonNode root = mapper.readTree(payload);
            if (root == null || root.path("schemaVersion").asInt(-1) != 1
                || !"ORDER_SNAPSHOT".equals(root.path("type").asText())) throw invalid();
            String eventId = root.path("eventId").asText("");
            String orderIdText = root.path("orderId").asText("");
            String status = root.path("status").asText("");
            if (eventId.isBlank() || eventId.length() > 255 || !orderIdText.matches("[1-9][0-9]*")
                || !STATUSES.contains(status) || !root.path("revision").canConvertToLong()
                || !root.path("total").canConvertToLong() || !root.path("items").isArray()) throw invalid();
            long id = Long.parseLong(orderIdText);
            long revision = root.path("revision").longValue();
            long total = root.path("total").longValue();
            if (revision < 0 || total < 0 || total > 9_007_199_254_740_991L) throw invalid();
            Instant occurredAt = Instant.parse(root.path("occurredAt").asText());
            Instant createdAt = Instant.parse(root.path("createdAt").asText());
            if (occurredAt.isBefore(createdAt)) throw invalid();
            List<Line> lines = new ArrayList<>();
            long calculated = 0;
            for (JsonNode item : root.path("items")) {
                String productId = item.path("productId").asText("");
                String name = item.path("name").asText("");
                if (!productId.matches("[1-9][0-9]*") || name.isBlank() || name.length() > 255
                    || !item.path("quantity").canConvertToInt() || !item.path("unitPrice").canConvertToLong()) throw invalid();
                int quantity = item.path("quantity").intValue();
                long price = item.path("unitPrice").longValue();
                if (quantity < 1 || price < 0) throw invalid();
                calculated = Math.addExact(calculated, Math.multiplyExact(quantity, price));
                lines.add(new Line(productId, name, quantity, price));
            }
            if (lines.isEmpty() || lines.size() > 100 || calculated != total) throw invalid();
            return new ReportOrderEvent(eventId, id, revision, occurredAt, createdAt, status, total, List.copyOf(lines));
        } catch (Exception ex) {
            if (ex instanceof InvalidEvent invalid) throw invalid;
            throw new InvalidEvent("Evento de pedido inválido", ex);
        }
    }
    private static InvalidEvent invalid() { return new InvalidEvent("Evento de pedido inválido"); }
    public static class InvalidEvent extends RuntimeException {
        public InvalidEvent(String message) { super(message); }
        public InvalidEvent(String message, Throwable cause) { super(message, cause); }
    }
}
