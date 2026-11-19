package com.pedidos360.pedidos.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.Version;
import jakarta.persistence.Table;
import jakarta.persistence.Index;

@Entity
@Table(indexes = @Index(name = "idx_pedido_owner", columnList = "customer_id,customer_tenant_id"))
public class Pedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String cliente;
    @Column(length = 2000)
    private String detalle;
    private Double total;
    @Column(name = "total_clp")
    private Long totalClp;
    private String estado;
    private LocalDateTime fecha;
    @Column(name = "created_at_utc")
    private Instant createdAtUtc;
    @Column(name = "reservation_key", unique = true)
    private String reservationKey;
    @Column(name = "inventory_state")
    private String inventoryState;
    private String requestActorName;
    private String requestActorEmail;
    private String requestActorId;
    private String requestActorTenantId;
    private String requestActorRole;

    @Column(name = "customer_id")
    private String customerId;
    @Column(name = "customer_tenant_id")
    private String customerTenantId;
    private OffsetDateTime updatedAt;
    @Version
    private Long version;
    @ElementCollection
    @CollectionTable(name = "pedido_items", joinColumns = @JoinColumn(name = "pedido_id"))
    @OrderColumn(name = "position")
    private List<PedidoItem> items = new ArrayList<>();
    @ElementCollection
    @CollectionTable(name = "pedido_history", joinColumns = @JoinColumn(name = "pedido_id"))
    @OrderColumn(name = "position")
    private List<PedidoStatusEvent> history = new ArrayList<>();

    public Pedido() {
    }

    public Pedido(String cliente, String detalle, Double total, String estado) {
        this.cliente = cliente;
        this.detalle = detalle;
        this.total = total;
        this.estado = estado;
        this.fecha = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCliente() {
        return cliente;
    }

    public void setCliente(String cliente) {
        this.cliente = cliente;
    }

    public String getDetalle() {
        return detalle;
    }

    public void setDetalle(String detalle) {
        this.detalle = detalle;
    }

    public Double getTotal() {
        return total;
    }

    public void setTotal(Double total) {
        this.total = total;
    }
    public Long getTotalClp() {
        if (totalClp != null) return totalClp;
        if (total == null || !Double.isFinite(total) || total < 0 || total != Math.rint(total)
            || total > 9_007_199_254_740_991d) return null;
        return total.longValue();
    }
    public void setTotalClp(Long value) {
        this.totalClp = value;
        this.total = value == null ? null : value.doubleValue();
    }
    public Instant getCreatedAtUtc() { return createdAtUtc; }
    public void setCreatedAtUtc(Instant value) { this.createdAtUtc = value; }
    public String getReservationKey() { return reservationKey; }
    public void setReservationKey(String value) { this.reservationKey = value; }
    public String getInventoryState() { return inventoryState; }
    public void setInventoryState(String value) { this.inventoryState = value; }
    public String getRequestActorName() { return requestActorName; }
    public void setRequestActorName(String value) { this.requestActorName = value; }
    public String getRequestActorEmail() { return requestActorEmail; }
    public void setRequestActorEmail(String value) { this.requestActorEmail = value; }
    public String getRequestActorId() { return requestActorId; }
    public void setRequestActorId(String value) { this.requestActorId = value; }
    public String getRequestActorTenantId() { return requestActorTenantId; }
    public void setRequestActorTenantId(String value) { this.requestActorTenantId = value; }
    public String getRequestActorRole() { return requestActorRole; }
    public void setRequestActorRole(String value) { this.requestActorRole = value; }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    public LocalDateTime getFecha() {
        return fecha;
    }

    public void setFecha(LocalDateTime fecha) {
        this.fecha = fecha;
    }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getCustomerTenantId() { return customerTenantId; }
    public void setCustomerTenantId(String customerTenantId) { this.customerTenantId = customerTenantId; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Long getVersion() { return version; }
    public List<PedidoItem> getItems() { return items; }
    public void setItems(List<PedidoItem> items) { this.items = items; }
    public List<PedidoStatusEvent> getHistory() { return history; }
    public void setHistory(List<PedidoStatusEvent> history) { this.history = history; }
}
