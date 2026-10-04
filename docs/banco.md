Banco: **MySQL 8.4**, `utf8mb4`, datas em **UTC** (`DATETIME(6)`), UUID em `CHAR(36)`.

### notification_template (V1)

| Coluna | Tipo | Regras |
|---|---|---|
| id | CHAR(36) | PK |
| code | VARCHAR(100) | collation `utf8mb4_bin` (sensível a maiúsculas) |
| channel | VARCHAR(20) | EMAIL, SMS, PUSH |
| subject | VARCHAR(255) | nulo permitido |
| content | TEXT | usa `{{variavel}}` |
| created_at, updated_at | DATETIME(6) | |

Único: `(code, channel)`.

### notification (V1, com colunas ligadas nas versões seguintes)

| Coluna | Tipo | Regras |
|---|---|---|
| id | CHAR(36) | PK |
| application_id | CHAR(36) | nulo até a V2; FK para `application` na V2 |
| schedule_id | CHAR(36) | nulo; coluna e FK criadas na V4 |
| channel | VARCHAR(20) | |
| recipient | VARCHAR(320) | |
| template_code | VARCHAR(100) | collation `utf8mb4_bin`; **sem FK** para o template, de propósito (o histórico não pode quebrar se o template mudar) |
| payload | JSON | variáveis do template |
| status | VARCHAR(20) | SCHEDULED, PENDING, QUEUED, PROCESSING, SENT, FAILED, CANCELED |
| attempts | INT | padrão 0 |
| scheduled_at | DATETIME(6) | nulo para envio imediato |
| sent_at | DATETIME(6) | nulo até enviar |
| last_error | TEXT | |
| idempotency_key | VARCHAR(255) | |
| created_at, updated_at | DATETIME(6) | |

Único: `(application_id, idempotency_key)`. Índices: `(status, scheduled_at)` e `(created_at)`.

### application (V2)

| Coluna | Tipo | Regras |
|---|---|---|
| id | CHAR(36) | PK |
| name | VARCHAR(100) | único |
| api_key_hash | CHAR(64) | único; SHA-256 em hexadecimal. **Nunca guardar a chave em texto puro** |
| active | BOOLEAN | padrão true |
| created_at, updated_at | DATETIME(6) | |

### notification_attempt (V3)

| Coluna | Tipo | Regras |
|---|---|---|
| id | CHAR(36) | PK |
| notification_id | CHAR(36) | FK para `notification`, `ON DELETE CASCADE` |
| attempt_number | INT | |
| status | VARCHAR(20) | SUCCESS ou FAILURE |
| error_message | TEXT | nulo em sucesso |
| started_at, finished_at | DATETIME(6) | |

Único: `(notification_id, attempt_number)`.

### notification_schedule (V4)

| Coluna | Tipo | Regras |
|---|---|---|
| id | CHAR(36) | PK |
| application_id | CHAR(36) | FK, obrigatório |
| name | VARCHAR(100) | |
| cron | VARCHAR(100) | 6 campos (formato do Spring) |
| timezone | VARCHAR(64) | ex.: America/Sao_Paulo |
| channel, recipient, template_code | como em `notification` | |
| payload | JSON | |
| active | BOOLEAN | |
| start_at, end_at | DATETIME(6) | nulos permitidos |
| next_run_at, last_run_at | DATETIME(6) | |
| created_at, updated_at | DATETIME(6) | |

Único: `(application_id, name)`. Índice: `(active, next_run_at)`.
Na V4 também: `notification.schedule_id` com FK para `notification_schedule`, `ON DELETE SET NULL`.

### Relações

- `application` 1:N `notification`
- `application` 1:N `notification_schedule`
- `notification_schedule` 1:N `notification`
- `notification` 1:N `notification_attempt`
- `notification` -> `notification_template` por `(template_code, channel)`, **sem FK**
