ALTER TABLE application
    ADD COLUMN rate_limit_per_minute INT NULL,
    ADD COLUMN api_key_hash_previous CHAR(64) NULL,
    ADD COLUMN previous_key_expires_at DATETIME(6) NULL,
    ADD UNIQUE KEY uk_application_previous_key (api_key_hash_previous);
ALTER TABLE notification
    ADD COLUMN request_hash CHAR(64) NULL,
    ADD COLUMN recovery_count INT NOT NULL DEFAULT 0,
    ADD COLUMN anonymized_at DATETIME(6) NULL,
    ADD COLUMN request_id VARCHAR(64) NULL,
    ADD KEY idx_notification_status_updated (status, updated_at),
    ADD KEY idx_notification_app_created (application_id, created_at);
