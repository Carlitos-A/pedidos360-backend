package com.pedidos360.pedidos.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.OffsetDateTime;

@Entity
public class VerifiedOwnerMapping {
    @Id private Long orderId;
    private String customerId;
    private String customerTenantId;
    private String verifiedBy;
    private OffsetDateTime verifiedAt;
    public VerifiedOwnerMapping() {}
    public VerifiedOwnerMapping(Long orderId, String customerId, String customerTenantId, String verifiedBy, OffsetDateTime verifiedAt) {
        this.orderId = orderId; this.customerId = customerId; this.customerTenantId = customerTenantId;
        this.verifiedBy = verifiedBy; this.verifiedAt = verifiedAt;
    }
    public Long getOrderId() { return orderId; }
    public String getCustomerId() { return customerId; }
    public String getCustomerTenantId() { return customerTenantId; }
    public String getVerifiedBy() { return verifiedBy; }
    public OffsetDateTime getVerifiedAt() { return verifiedAt; }
}
