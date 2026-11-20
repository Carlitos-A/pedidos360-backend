-- Run against auditdb before starting audit-service with the prod profile.
CREATE TABLE audit_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    type VARCHAR(40) NOT NULL,
    description VARCHAR(255) NOT NULL,
    actor_email VARCHAR(255) NULL,
    actor_id VARCHAR(255) NULL,
    actor_tenant_id VARCHAR(255) NULL,
    occurred_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_audit_event_id UNIQUE (event_id),
    INDEX idx_audit_occurred_id (occurred_at, id),
    INDEX idx_audit_actor_occurred (actor_email, occurred_at),
    INDEX idx_audit_type_occurred (type, occurred_at)
);

CREATE TABLE entra_sign_in_cursor (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    last_completed_at DATETIME(6) NULL
);

-- Run once after the table is created, while the legacy writer is still active.
-- Use UTC for both MySQL sessions and repeat after switching producers to Kafka.
INSERT INTO audit_event (event_id, type, description, actor_email, occurred_at)
SELECT event_key, type, description, actor_email, occurred_at
FROM pedidosdb.audit_event
ON DUPLICATE KEY UPDATE event_id = event_id;
