-- Mantém todos os templates existentes como globais, sem perder histórico.
ALTER TABLE notification_template
    ADD COLUMN application_id CHAR(36) NULL,
    ADD COLUMN scope_key VARCHAR(36) GENERATED ALWAYS AS (COALESCE(application_id, 'GLOBAL')) STORED,
    ADD CONSTRAINT fk_template_application FOREIGN KEY (application_id) REFERENCES application(id),
    DROP INDEX uk_template_code_channel,
    ADD UNIQUE KEY uk_template_scope_code_channel (scope_key, code, channel);
