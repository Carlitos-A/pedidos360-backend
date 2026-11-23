CREATE TABLE pedido (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    cliente VARCHAR(255),
    detalle VARCHAR(2000),
    total DOUBLE,
    total_clp BIGINT,
    estado VARCHAR(255),
    fecha DATETIME(6),
    created_at_utc DATETIME(6),
    reservation_key VARCHAR(255),
    inventory_state VARCHAR(255),
    request_actor_name VARCHAR(255),
    request_actor_email VARCHAR(255),
    request_actor_id VARCHAR(255),
    request_actor_tenant_id VARCHAR(255),
    request_actor_role VARCHAR(255),
    customer_id VARCHAR(255),
    customer_tenant_id VARCHAR(255),
    updated_at DATETIME(6),
    version BIGINT,
    CONSTRAINT uq_pedido_reservation_key UNIQUE (reservation_key),
    INDEX idx_pedido_owner (customer_id, customer_tenant_id)
);

CREATE TABLE pedido_items (
    pedido_id BIGINT NOT NULL,
    position INT NOT NULL,
    product_id VARCHAR(255),
    name VARCHAR(255),
    quantity INT,
    unit_price BIGINT,
    PRIMARY KEY (pedido_id, position),
    CONSTRAINT fk_pedido_items_order FOREIGN KEY (pedido_id) REFERENCES pedido(id)
);

CREATE TABLE pedido_history (
    pedido_id BIGINT NOT NULL,
    position INT NOT NULL,
    status VARCHAR(255),
    previous_status VARCHAR(255),
    changed_at DATETIME(6),
    actor_name VARCHAR(255),
    actor_role VARCHAR(255),
    actor_id VARCHAR(255),
    actor_tenant_id VARCHAR(255),
    PRIMARY KEY (pedido_id, position),
    CONSTRAINT fk_pedido_history_order FOREIGN KEY (pedido_id) REFERENCES pedido(id)
);

CREATE TABLE pending_order_update (
    order_id BIGINT NOT NULL PRIMARY KEY,
    expected_version BIGINT,
    detail VARCHAR(2000)
);

CREATE TABLE pending_order_items (
    order_id BIGINT NOT NULL,
    position INT NOT NULL,
    product_id VARCHAR(255),
    name VARCHAR(255),
    quantity INT,
    unit_price BIGINT,
    PRIMARY KEY (order_id, position),
    CONSTRAINT fk_pending_order_items FOREIGN KEY (order_id) REFERENCES pending_order_update(order_id)
);

CREATE TABLE pending_inventory_release (
    order_id BIGINT NOT NULL PRIMARY KEY
);

CREATE TABLE verified_owner_mapping (
    order_id BIGINT NOT NULL PRIMARY KEY,
    customer_id VARCHAR(255),
    customer_tenant_id VARCHAR(255),
    verified_by VARCHAR(255),
    verified_at DATETIME(6)
);

CREATE TABLE audit_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_key VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    description VARCHAR(255) NOT NULL,
    actor_email VARCHAR(255),
    occurred_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_legacy_audit_event_key UNIQUE (event_key),
    INDEX idx_audit_occurred (occurred_at)
);

CREATE TABLE order_outbox (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    exchange_name VARCHAR(255) NOT NULL,
    routing_key VARCHAR(255) NOT NULL,
    payload LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6),
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    INDEX idx_order_outbox_pending (published_at, created_at)
);

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

CREATE TABLE rejected_rabbit_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    fingerprint VARCHAR(64) NOT NULL,
    dlq_name VARCHAR(255) NOT NULL,
    source_queue VARCHAR(255),
    death_reason VARCHAR(100),
    message_id VARCHAR(255),
    payload_sha256 VARCHAR(64) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_rejected_rabbit_fingerprint UNIQUE (fingerprint),
    INDEX idx_rejected_rabbit_observed (observed_at, id)
);
