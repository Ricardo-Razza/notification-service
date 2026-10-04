-- V4: Criação da tabela notification_attempt para auditoria e histórico de tentativas de envio
CREATE TABLE notification_attempt (
    id              CHAR(36)     NOT NULL,
    notification_id CHAR(36)     NOT NULL,
    attempt_number  INT          NOT NULL,
    status          VARCHAR(20)  NOT NULL, -- SUCCESS ou FAILURE
    error_message   TEXT         NULL,
    started_at      DATETIME(6)  NOT NULL,
    finished_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_attempt (notification_id, attempt_number),
    CONSTRAINT fk_attempt_notification
        FOREIGN KEY (notification_id) REFERENCES notification (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
