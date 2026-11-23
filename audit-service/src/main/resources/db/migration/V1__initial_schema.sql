CREATE TABLE audit_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    type VARCHAR(40) NOT NULL,
    description VARCHAR(255) NOT NULL,
    actor_email VARCHAR(255),
    actor_id VARCHAR(255),
    actor_tenant_id VARCHAR(255),
    occurred_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_audit_event_id UNIQUE (event_id),
    INDEX idx_audit_occurred_id (occurred_at, id),
    INDEX idx_audit_actor_occurred (actor_email, occurred_at),
    INDEX idx_audit_type_occurred (type, occurred_at)
);

CREATE TABLE entra_sign_in_cursor (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    last_completed_at DATETIME(6)
);

CREATE TABLE rejected_kafka_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    dlt_topic VARCHAR(255) NOT NULL,
    dlt_partition INT NOT NULL,
    dlt_offset BIGINT NOT NULL,
    source_topic VARCHAR(255),
    source_partition INT,
    source_offset BIGINT,
    cause VARCHAR(500),
    rejected_at DATETIME(6),
    observed_at DATETIME(6) NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    CONSTRAINT uq_rejected_kafka_position UNIQUE (dlt_topic, dlt_partition, dlt_offset),
    INDEX idx_rejected_kafka_observed (observed_at, id)
);
