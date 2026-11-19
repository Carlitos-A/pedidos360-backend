package com.pedidos360.productos.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import jakarta.persistence.Column;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(indexes = @Index(name = "idx_producto_owner", columnList = "customer_id,customer_tenant_id"))
public class Producto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;
    private String categoria;
    private Double precio;
    @Column(name = "price_clp")
    private Long priceClp;
    private Integer stock;
    @Column(name = "customer_id")
    private String customerId;
    @Column(name = "customer_tenant_id")
    private String customerTenantId;
    @Version
    private Long version;

    public Producto() {
    }

    public Producto(String nombre, String categoria, Double precio) {
        this.nombre = nombre;
        this.categoria = categoria;
        this.precio = precio;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getCategoria() {
        return categoria;
    }

    public void setCategoria(String categoria) {
        this.categoria = categoria;
    }

    public Double getPrecio() {
        return precio;
    }

    public void setPrecio(Double precio) {
        this.precio = precio;
    }
    public Long getPriceClp() {
        if (priceClp != null) return priceClp;
        if (precio == null || !Double.isFinite(precio) || precio < 0 || precio != Math.rint(precio)
            || precio > 9_007_199_254_740_991d) return null;
        return precio.longValue();
    }
    public void setPriceClp(Long priceClp) {
        this.priceClp = priceClp;
        this.precio = priceClp == null ? null : priceClp.doubleValue();
    }
    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getCustomerTenantId() { return customerTenantId; }
    public void setCustomerTenantId(String customerTenantId) { this.customerTenantId = customerTenantId; }
}
