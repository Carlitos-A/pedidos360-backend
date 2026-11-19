-- MySQL / pedidosdb. Ejecutar con respaldo antes de desplegar la versión nueva.
-- Los eventos históricos conservan NULL en las columnas nuevas.
ALTER TABLE pedido_history
    ADD COLUMN previous_status VARCHAR(255) NULL,
    ADD COLUMN actor_id VARCHAR(255) NULL,
    ADD COLUMN actor_tenant_id VARCHAR(255) NULL;

ALTER TABLE pedido
    ADD COLUMN request_actor_id VARCHAR(255) NULL,
    ADD COLUMN request_actor_tenant_id VARCHAR(255) NULL,
    ADD COLUMN request_actor_role VARCHAR(255) NULL;

CREATE TABLE order_outbox (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    exchange_name VARCHAR(255) NOT NULL,
    routing_key VARCHAR(255) NOT NULL,
    payload LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    INDEX idx_order_outbox_pending (published_at, created_at)
);
