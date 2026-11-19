package com.pedidos360.pedidos.model;

import java.util.Locale;
import java.util.Optional;

public enum OrderStatus {
    CREATED, ACCEPTED, IN_PROGRESS, COMPLETED, CANCELLED;

    public static Optional<OrderStatus> fromStored(String value) {
        if (value == null) return Optional.empty();
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        normalized = switch (normalized) {
            case "PENDIENTE" -> "CREATED";
            case "EN_PREPARACION" -> "IN_PROGRESS";
            case "ENTREGADO" -> "COMPLETED";
            default -> normalized;
        };
        try { return Optional.of(valueOf(normalized)); }
        catch (IllegalArgumentException ex) { return Optional.empty(); }
    }

    public static Optional<OrderStatus> fromInput(String value) {
        if (value == null) return Optional.empty();
        try { return Optional.of(valueOf(value.strip().toUpperCase(Locale.ROOT))); }
        catch (IllegalArgumentException ex) { return Optional.empty(); }
    }

    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case CREATED -> next == ACCEPTED || next == CANCELLED;
            case ACCEPTED -> next == IN_PROGRESS || next == CANCELLED;
            case IN_PROGRESS -> next == COMPLETED || next == CANCELLED;
            case COMPLETED, CANCELLED -> false;
        };
    }
}
