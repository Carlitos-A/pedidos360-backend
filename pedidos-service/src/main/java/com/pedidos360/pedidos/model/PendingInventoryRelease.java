package com.pedidos360.pedidos.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class PendingInventoryRelease {
    @Id
    private Long orderId;
    protected PendingInventoryRelease() {}
    public PendingInventoryRelease(Long orderId) { this.orderId = orderId; }
    public Long getOrderId() { return orderId; }
}
