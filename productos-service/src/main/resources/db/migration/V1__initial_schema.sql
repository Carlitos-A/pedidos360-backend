CREATE TABLE producto (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(255),
    categoria VARCHAR(255),
    precio DOUBLE,
    price_clp BIGINT,
    stock INT,
    customer_id VARCHAR(255),
    customer_tenant_id VARCHAR(255),
    version BIGINT,
    INDEX idx_producto_owner (customer_id, customer_tenant_id)
);

CREATE TABLE product_reservation (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    customer_id VARCHAR(255),
    customer_tenant_id VARCHAR(255)
);

CREATE TABLE product_reservation_lines (
    reservation_id VARCHAR(255) NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT,
    PRIMARY KEY (reservation_id, product_id),
    CONSTRAINT fk_reservation_lines FOREIGN KEY (reservation_id) REFERENCES product_reservation(id)
);

CREATE TABLE product_reservation_prices (
    reservation_id VARCHAR(255) NOT NULL,
    product_id BIGINT NOT NULL,
    unit_price_clp BIGINT,
    PRIMARY KEY (reservation_id, product_id),
    CONSTRAINT fk_reservation_prices FOREIGN KEY (reservation_id) REFERENCES product_reservation(id)
);

CREATE TABLE product_reservation_names (
    reservation_id VARCHAR(255) NOT NULL,
    product_id BIGINT NOT NULL,
    product_name VARCHAR(255),
    PRIMARY KEY (reservation_id, product_id),
    CONSTRAINT fk_reservation_names FOREIGN KEY (reservation_id) REFERENCES product_reservation(id)
);

CREATE TABLE product_audit_outbox (
    event_id VARCHAR(255) NOT NULL PRIMARY KEY,
    product_id VARCHAR(255),
    actor_email VARCHAR(255),
    actor_id VARCHAR(255),
    actor_tenant_id VARCHAR(255),
    destination VARCHAR(32),
    occurred_at DATETIME(6)
);
