CREATE TABLE admin_user (
    id CHAR(36) NOT NULL PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until DATETIME(6) NULL,
    last_login_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_admin_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE admin_audit_log (
    id CHAR(36) NOT NULL PRIMARY KEY,
    -- Nulo permite auditar tentativas de login com usuário inexistente, sem guardar o e-mail informado.
    admin_user_id CHAR(36) NULL,
    action VARCHAR(60) NOT NULL,
    target_type VARCHAR(40) NOT NULL,
    target_id CHAR(36) NULL,
    details JSON NULL,
    ip VARCHAR(45) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_audit_admin_user FOREIGN KEY (admin_user_id) REFERENCES admin_user(id),
    KEY idx_audit_created (created_at),
    KEY idx_audit_user (admin_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
