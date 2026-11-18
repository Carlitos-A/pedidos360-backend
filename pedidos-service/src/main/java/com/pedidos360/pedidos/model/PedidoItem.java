package com.pedidos360.pedidos.model;

import jakarta.persistence.Embeddable;

@Embeddable
public class PedidoItem {
    private String productId;
    private String name;
    private Integer quantity;
    private Long unitPrice;
    public PedidoItem() {}
    public PedidoItem(String productId, String name, Integer quantity, Long unitPrice) {
        this.productId = productId; this.name = name; this.quantity = quantity; this.unitPrice = unitPrice;
    }
    public String getProductId() { return productId; }
    public String getName() { return name; }
    public Integer getQuantity() { return quantity; }
    public Long getUnitPrice() { return unitPrice; }
}
