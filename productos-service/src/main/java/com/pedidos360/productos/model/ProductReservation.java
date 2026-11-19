package com.pedidos360.productos.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import java.util.HashMap;
import java.util.Map;

@Entity
public class ProductReservation {
    @Id
    private String id;
    private String customerId;
    private String customerTenantId;
    @ElementCollection
    @CollectionTable(name = "product_reservation_lines", joinColumns = @JoinColumn(name = "reservation_id"))
    @MapKeyColumn(name = "product_id")
    @Column(name = "quantity")
    private Map<Long, Integer> quantities = new HashMap<>();
    @ElementCollection
    @CollectionTable(name = "product_reservation_prices", joinColumns = @JoinColumn(name = "reservation_id"))
    @MapKeyColumn(name = "product_id")
    @Column(name = "unit_price_clp")
    private Map<Long, Long> prices = new HashMap<>();
    @ElementCollection
    @CollectionTable(name = "product_reservation_names", joinColumns = @JoinColumn(name = "reservation_id"))
    @MapKeyColumn(name = "product_id")
    @Column(name = "product_name")
    private Map<Long, String> names = new HashMap<>();

    protected ProductReservation() {}
    public ProductReservation(String id, String customerId, String customerTenantId) {
        this.id = id; this.customerId = customerId; this.customerTenantId = customerTenantId;
    }
    public String getCustomerId() { return customerId; }
    public String getCustomerTenantId() { return customerTenantId; }
    public Map<Long, Integer> getQuantities() { return quantities; }
    public Map<Long, Long> getPrices() { return prices; }
    public Map<Long, String> getNames() { return names; }
}
