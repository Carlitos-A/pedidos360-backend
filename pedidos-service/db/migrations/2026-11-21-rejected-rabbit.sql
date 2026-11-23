-- Apply to pedidosdb before enabling RABBIT_DLQ_MONITOR_ENABLED in prod.
CREATE TABLE rejected_rabbit_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    fingerprint VARCHAR(64) NOT NULL,
    dlq_name VARCHAR(255) NOT NULL,
    source_queue VARCHAR(255) NULL,
    death_reason VARCHAR(100) NULL,
    message_id VARCHAR(255) NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_rejected_rabbit_fingerprint UNIQUE (fingerprint),
    INDEX idx_rejected_rabbit_observed (observed_at, id)
);
