package com.pedidos360.pedidos.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Column;
import jakarta.persistence.OrderColumn;
import java.util.ArrayList;
import java.util.List;

@Entity
public class PendingOrderUpdate {
    @Id
    private Long orderId;
    private Long expectedVersion;
    @Column(length = 2000)
    private String detail;
    @ElementCollection
    @CollectionTable(name = "pending_order_items", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "position")
    private List<PedidoItem> items = new ArrayList<>();

    protected PendingOrderUpdate() {}
    public PendingOrderUpdate(Long orderId, Long expectedVersion, String detail, List<PedidoItem> items) {
        this.orderId = orderId; this.expectedVersion = expectedVersion; this.detail = detail;
        this.items.addAll(items);
    }
    public Long getOrderId() { return orderId; }
    public Long getExpectedVersion() { return expectedVersion; }
    public String getDetail() { return detail; }
    public List<PedidoItem> getItems() { return items; }
}
