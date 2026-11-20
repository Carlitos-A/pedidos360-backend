-- Nullable for outbox messages created before the Kafka rollout.
ALTER TABLE product_audit_outbox
    ADD COLUMN actor_id VARCHAR(255) NULL,
    ADD COLUMN actor_tenant_id VARCHAR(255) NULL,
    ADD COLUMN destination VARCHAR(32) NOT NULL DEFAULT 'rabbit';
