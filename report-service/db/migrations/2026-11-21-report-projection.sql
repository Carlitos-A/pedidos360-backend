-- Apply to reportdb before starting report-service with the prod profile.
-- Use UTC for the MySQL session. No historical rows are invented here.
CREATE TABLE report_order (
    id BIGINT NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    total BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    items_json LONGTEXT NOT NULL,
    INDEX idx_report_created (created_at),
    INDEX idx_report_completed (completed_at)
);

CREATE TABLE report_receipt (
    event_id VARCHAR(255) NOT NULL PRIMARY KEY,
    processed_at DATETIME(6) NULL,
    INDEX idx_report_processed (processed_at)
);
