-- Notification Service - schema inicial (MySQL 8)
-- Convenções: UUID em CHAR(36), datas em UTC, texto em utf8mb4.

CREATE TABLE notification_template (
    id          CHAR(36)     NOT NULL,
    -- utf8mb4_bin: comparação sensível a maiúsculas/minúsculas (RESET_PASSWORD <> reset_password)
    code        VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    channel     VARCHAR(20)  NOT NULL,
    subject     VARCHAR(255) NULL,
    content     TEXT         NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_template_code_channel (code, channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notification (
    id               CHAR(36)     NOT NULL,
    -- Preenchido a partir da V2 (API Keys). Nulo por enquanto.
    application_id   CHAR(36)     NULL,
    channel          VARCHAR(20)  NOT NULL,
    recipient        VARCHAR(320) NOT NULL,
    template_code    VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    payload          JSON         NULL,
    -- SCHEDULED, PENDING, QUEUED, PROCESSING, SENT, FAILED, CANCELED
    status           VARCHAR(20)  NOT NULL,
    attempts         INT          NOT NULL DEFAULT 0,
    -- Já modelado para o agendamento (V4), nulo para envios imediatos.
    scheduled_at     DATETIME(6)  NULL,
    sent_at          DATETIME(6)  NULL,
    last_error       TEXT         NULL,
    -- Usado na V2. Várias linhas com NULL são permitidas no índice único.
    idempotency_key  VARCHAR(255) NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_app_idempotency (application_id, idempotency_key),
    KEY idx_notification_status_scheduled (status, scheduled_at),
    KEY idx_notification_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
