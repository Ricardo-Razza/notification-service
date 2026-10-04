-- V3: Criação da tabela application e chave estrangeira para notification
-- API Keys: identificação e autenticação de sistemas clientes

CREATE TABLE application (
    id             CHAR(36)     NOT NULL,
    name           VARCHAR(100) NOT NULL,
    api_key_hash   CHAR(64)     NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_application_name (name),
    UNIQUE KEY uk_application_api_key_hash (api_key_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Adiciona a chave estrangeira em notification.application_id (linhas antigas com NULL continuam válidas)
ALTER TABLE notification
    ADD CONSTRAINT fk_notification_application
    FOREIGN KEY (application_id) REFERENCES application (id)
    ON DELETE RESTRICT;
