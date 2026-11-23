CREATE TABLE registered_customer (
    customer_id VARCHAR(255) NOT NULL,
    customer_tenant_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    last_seen_at DATETIME(6) NOT NULL,
    PRIMARY KEY (customer_id, customer_tenant_id),
    INDEX idx_registered_customer_name (customer_tenant_id, name)
);
