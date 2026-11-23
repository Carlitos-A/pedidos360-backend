package com.pedidos360.pedidos.model;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
public class RegisteredCustomer {
    @Embeddable
    public static class Key implements Serializable {
        private String customerId;
        private String customerTenantId;
        protected Key() {}
        public Key(String customerId, String customerTenantId) {
            this.customerId = customerId; this.customerTenantId = customerTenantId;
        }
        public String getCustomerId() { return customerId; }
        public String getCustomerTenantId() { return customerTenantId; }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(customerId, key.customerId)
                && Objects.equals(customerTenantId, key.customerTenantId);
        }
        @Override public int hashCode() { return Objects.hash(customerId, customerTenantId); }
    }
    @EmbeddedId private Key id;
    private String name;
    private String email;
    private OffsetDateTime lastSeenAt;
    protected RegisteredCustomer() {}
    public RegisteredCustomer(String customerId, String customerTenantId) {
        this.id = new Key(customerId, customerTenantId);
    }
    public String getCustomerId() { return id.getCustomerId(); }
    public String getCustomerTenantId() { return id.getCustomerTenantId(); }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setName(String name) { this.name = name; }
    public void setEmail(String email) { this.email = email; }
    public void setLastSeenAt(OffsetDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
