-- V5: Criação da tabela notification_schedule e adição da coluna schedule_id na tabela notification
CREATE TABLE notification_schedule (
    id              CHAR(36)     NOT NULL,
    application_id  CHAR(36)     NOT NULL,
    name            VARCHAR(100) NOT NULL,
    cron            VARCHAR(100) NOT NULL,
    timezone        VARCHAR(64)  NOT NULL,
    channel         VARCHAR(20)  NOT NULL,
    recipient       VARCHAR(320) NOT NULL,
    template_code   VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    payload         JSON         NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    start_at        DATETIME(6)  NULL,
    end_at          DATETIME(6)  NULL,
    next_run_at     DATETIME(6)  NULL,
    last_run_at     DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_app_name (application_id, name),
    KEY idx_schedule_active_next_run (active, next_run_at),
    CONSTRAINT fk_schedule_application
        FOREIGN KEY (application_id) REFERENCES application (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE notification
    ADD COLUMN schedule_id CHAR(36) NULL AFTER application_id,
    ADD CONSTRAINT fk_notification_schedule
        FOREIGN KEY (schedule_id) REFERENCES notification_schedule (id)
        ON DELETE SET NULL;
