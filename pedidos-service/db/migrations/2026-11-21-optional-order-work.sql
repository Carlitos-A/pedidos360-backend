-- Apply to pedidosdb before deploying functional Rabbit notification/ticket consumers.
CREATE TABLE order_notification (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    order_id BIGINT NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    customer_tenant_id VARCHAR(255) NOT NULL,
    message VARCHAR(255) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_order_notification_event UNIQUE (event_id),
    INDEX idx_notification_owner (customer_id, customer_tenant_id, occurred_at),
    INDEX idx_notification_order (order_id)
);

CREATE TABLE order_ticket (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    order_id BIGINT NOT NULL,
    ticket_number VARCHAR(64) NOT NULL,
    customer_name VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    customer_tenant_id VARCHAR(255) NOT NULL,
    total_clp BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    items_json TEXT NOT NULL,
    CONSTRAINT uq_order_ticket_event UNIQUE (event_id),
    CONSTRAINT uq_order_ticket_order UNIQUE (order_id),
    CONSTRAINT uq_order_ticket_number UNIQUE (ticket_number),
    INDEX idx_ticket_customer (customer_id, customer_tenant_id)
);
