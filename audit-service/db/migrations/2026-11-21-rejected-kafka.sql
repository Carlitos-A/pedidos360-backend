-- Apply to auditdb before enabling AUDIT_DLT_MONITOR_ENABLED in prod.
CREATE TABLE rejected_kafka_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    dlt_topic VARCHAR(255) NOT NULL,
    dlt_partition INT NOT NULL,
    dlt_offset BIGINT NOT NULL,
    source_topic VARCHAR(255) NULL,
    source_partition INT NULL,
    source_offset BIGINT NULL,
    cause VARCHAR(500) NULL,
    rejected_at DATETIME(6) NULL,
    observed_at DATETIME(6) NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    CONSTRAINT uq_rejected_kafka_position UNIQUE (dlt_topic, dlt_partition, dlt_offset),
    INDEX idx_rejected_kafka_observed (observed_at, id)
);
