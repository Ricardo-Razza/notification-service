# Notification Service

Serviço centralizado e desacoplado para disparo e gestão de notificações transacionais por múltiplos canais (E-mail, SMS, Push, WhatsApp).

Desenvolvido com **Java 21**, **Spring Boot 4**, **MySQL 8.4** e conformidade com o padrão de erros **RFC 9457 (ProblemDetail)**.

---

## Sumário

- [Visão Geral e Arquitetura](#visão-geral-e-arquitetura)
- [Pré-requisitos](#pré-requisitos)
- [Execução Local (Desenvolvimento)](#execução-local-desenvolvimento)
- [Execução dos Testes](#execução-dos-testes)
- [Deploy em Produção (VPS via Docker)](#deploy-em-produção-vps-via-docker)
- [Rotina de Backup e Restauração](#rotina-de-backup-e-restauração)
- [Endpoints Principais da API](#endpoints-principais-da-api)

---

## Visão Geral e Arquitetura

- **Transações Curtas e I/O Desacoplado:** Chamadas de mensageria e provedores ocorrem rigorosamente fora de transações de banco de dados, eliminando locks e saturação de pools.
- **Mensageria e Filas (RabbitMQ):** Disparo assíncrono com publisher confirms, mensagens persistentes, filas de retry com TTL e Dead Letter Queue (DLQ).
- **Formato de Erro Padronizado:** Erros de validação, conflito e falhas de negócio respondem via `ProblemDetail` (RFC 9457) sem expor stack traces.
- **Banco de Dados:** MySQL 8.4 (`utf8mb4`, collation `utf8mb4_unicode_ci`, datas em UTC `DATETIME(6)`), gerenciado via Flyway.

### Limite Conhecido e Resiliência da Fila (PENDING / QUEUED)

> [!WARNING]
> Ao receber uma solicitação de disparo (`POST /api/v1/notifications`), o serviço primeiro grava a notificação como `PENDING` no banco de dados e, logo em seguida, publica o identificador (`notificationId`) na fila do RabbitMQ com publisher confirms. Com a confirmação do broker, a notificação transiciona para `QUEUED` e a API responde `202 Accepted`.
>
> Se a publicação no RabbitMQ falhar (ex.: instabilidade no broker), a notificação permanece no banco de dados com status `PENDING` e a API responde `503 Service Unavailable` (RFC 9457).
>
> **Risco:** Notificações em estado `PENDING` sem mensagem correspondente na fila.
> **Evoluções planejadas:** Implementação do padrão *Transactional Outbox* ou rotina periódica de reconciliação para buscar e republicar registros `PENDING` com tempo de criação expirado.

---

## Pré-requisitos

- **Java 21** (JDK 21)
- **Docker** e **Docker Compose**
- Maven (ou o wrapper `./mvnw` incluído no projeto)

---

## Execução Local (Desenvolvimento)

No ambiente local, o banco de dados MySQL, o RabbitMQ e o emulador de e-mails **Mailpit** rodam via Docker Compose:

1. **Subir os serviços locais (MySQL, RabbitMQ e Mailpit):**
   ```bash
   docker compose up -d
   ```
   *Serviços disponíveis no host:*
   - **MySQL 8.4:** `127.0.0.1:3309` (interno `3306`)
   - **RabbitMQ AMQP:** `127.0.0.1:5672`
   - **RabbitMQ Management UI:** `http://localhost:15672` (usuário: `guest`, senha: `guest`)
   - **Mailpit SMTP:** `127.0.0.1:1025`
   - **Mailpit Web UI:** `http://localhost:8025`

2. **Executar a aplicação Spring Boot:**
   ```bash
   ./mvnw spring-boot:run
   ```

3. **Verificar a saúde da aplicação:**
   ```bash
   curl http://localhost:8080/actuator/health
   ```

4. **Acessar a caixa postal de testes:**
   Abra no navegador: `http://localhost:8025`

---

## Execução dos Testes

O projeto conta com testes unitários, testes de camada web com `MockMvc` e testes de ponta a ponta com **Testcontainers (MySQL 8.4)**:

```bash
./mvnw test
```

---

## Deploy em Produção (VPS via Docker)

A configuração de produção roda a aplicação e o MySQL isolados na mesma rede Docker bridge interna. **A porta do MySQL NÃO é exposta para o host/internet.**

### Passo a passo para subir na VPS:

1. **Clonar o repositório no servidor:**
   ```bash
   git clone <URL_DO_REPOSITORIO> notification-service
   cd notification-service
   ```

2. **Configurar as variáveis de ambiente:**
   ```bash
   cp .env.prod.example .env.prod
   nano .env.prod
   ```
   *Preencha com as senhas seguras do MySQL e credenciais do provedor SMTP (SendGrid, SES, Mailgun, etc.).*

3. **Validar a configuração do Compose:**
   ```bash
   docker compose --env-file .env.prod -f docker-compose.prod.yml config
   ```

4. **Construir a imagem e iniciar os containers em background:**
   ```bash
   docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
   ```

5. **Verificar o status e logs:**
   ```bash
   docker compose -f docker-compose.prod.yml ps
   docker compose -f docker-compose.prod.yml logs -f app
   ```

---

## Rotina de Backup e Restauração

### 1. Executar Backup Manual
O script [scripts/backup.sh](file:///C:/Users/razza/OneDrive/Área de Trabalho/dev/notification-service/scripts/backup.sh) gera um dump compactado via `mysqldump` direto do container e remove backups com mais de 7 dias:

```bash
chmod +x scripts/backup.sh
./scripts/backup.sh
```
Os arquivos gerados ficam no diretório `backups/notification_YYYYMMDD_HHMMSS.sql.gz`.

### 2. Agendar Backup Diário no Crontab (VPS)
Edite a crontab do servidor:
```bash
crontab -e
```
Adicione a linha para execução diária às 02:00 da manhã:
```cron
0 2 * * * /caminho/completo/notification-service/scripts/backup.sh >> /var/log/notification-backup.log 2>&1
```

### 3. Restauração do Banco de Dados
Para restaurar um backup existente em caso de desastre:

```bash
# 1. Descompacte e aplique o dump diretamente no container em execução:
gunzip < backups/notification_20261004_120000.sql.gz | docker exec -i notification-mysql-prod mysql -u notification_user -p"sua_senha" notification
```

---

---

## Fluxo Assíncrono e Mensageria (RabbitMQ)

O serviço opera de forma assíncrona desacoplada através do **RabbitMQ**, garantindo que operações de I/O de rede e provedores externos nunca ocorram dentro de transações abertas de banco de dados.

### 1. Diagrama de Fluxo

```text
[Cliente API] 
     │ POST /api/v1/notifications
     ▼
[NotificationService] ──(Transação curta 1)──> MySQL: Grava status PENDING
     │
     ├──> RabbitMQ Publisher (Publisher Confirms, Mensagens Persistentes)
     │       Exchange: notification.exchange | Routing Key: notification.send
     │
     └──(Transação curta 2)──> MySQL: Atualiza para QUEUED ──> Retorna 202 Accepted

                              │
                              ▼ (Fila: notification.queue)
                     [NotificationConsumer] (Worker)
                              │
                              ├──> Idempotência: Se já SENT/CANCELED, descarta com basicAck
                              ├──> Atualiza para PROCESSING
                              ├──> Renderiza template e envia via Sender (Fora de transação)
                              │
                   ┌──────────┴──────────┐
                   ▼                     ▼
               [Sucesso]              [Falha]
                   │                     │
          MySQL: SENT, sent_at   MySQL: Grava tentativa FAILURE, last_error
          Grava tentativa SUCCESS        │
          basicAck                       ├─ Tentativas < 3:
                                         │    Envia para Fila de Retry (10s / 30s / 2m)
                                         │    MySQL: Volta para QUEUED
                                         │    basicAck
                                         │
                                         └─ Tentativas esgotadas (>= 3):
                                              Envia para notification.dlq
                                              MySQL: FAILED
                                              basicAck
```

### 2. Topologia de Filas e Trocas

A topologia é totalmente declarada via código em `RabbitConfig`:

| Componente | Nome / Chave | Tipo | Finalidade |
|---|---|---|---|
| **Exchange** | `notification.exchange` | Direct | Ponto de entrada de publicações |
| **Fila Principal** | `notification.queue` | Durável | Consumida pelos workers concorrentes (`concurrency: 2`) |
| **Fila Retry 1** | `notification.retry.10s` | Durável, TTL 10s | Dead-Letter volta para `notification.queue` |
| **Fila Retry 2** | `notification.retry.30s` | Durável, TTL 30s | Dead-Letter volta para `notification.queue` |
| **Fila Retry 3** | `notification.retry.2m` | Durável, TTL 2min | Dead-Letter volta para `notification.queue` |
| **Fila DLQ** | `notification.dlq` | Durável | Mensagens com tentativas esgotadas ou malformadas |

### 3. Como Inspecionar a DLQ e Diagnosticar Notificações com Falha

Se uma notificação esgotar as tentativas ou possuir formato inválido, ela é roteada para a Dead Letter Queue (`notification.dlq`):

1. **Pela Interface Web do RabbitMQ:**
   - Acesse `http://localhost:15672` (login: `guest` / `guest`).
   - Vá na aba **Queues** -> clique em `notification.dlq`.
   - No painel **Get messages**, clique em **Get Message(s)** para visualizar o payload contendo o `notificationId`.

2. **Pela API de Notificações:**
   - Consulte os detalhes completos da notificação e todo o histórico de tentativas:
     ```bash
     curl -X GET http://localhost:8080/api/v1/notifications/{id} \
          -H "X-API-Key: {sua_api_key}"
     ```
   - A resposta inclui o histórico auditável de cada tentativa em `attemptsHistory`:
     ```json
     {
       "id": "e96f12ab-...",
       "status": "FAILED",
       "attempts": 3,
       "lastError": "Connection timed out connecting to SMTP",
       "attemptsHistory": [
         {
           "attemptNumber": 1,
           "status": "FAILURE",
           "errorMessage": "Connection timed out connecting to SMTP",
           "startedAt": "2026-10-04T18:00:00Z",
           "finishedAt": "2026-10-04T18:00:05Z"
         },
         {
           "attemptNumber": 2,
           "status": "FAILURE",
           "errorMessage": "Connection timed out connecting to SMTP",
           "startedAt": "2026-10-04T18:00:15Z",
           "finishedAt": "2026-10-04T18:00:20Z"
         },
         {
           "attemptNumber": 3,
           "status": "FAILURE",
           "errorMessage": "Connection timed out connecting to SMTP",
           "startedAt": "2026-10-04T18:00:50Z",
           "finishedAt": "2026-10-04T18:00:55Z"
         }
       ]
     }
     ```

### 4. Reprocessamento de Falhas (`POST /retry`)

Para reprocessar uma notificação com status `FAILED`:

```bash
curl -X POST http://localhost:8080/api/v1/notifications/{id}/retry \
     -H "X-API-Key: {sua_api_key}"
```
- **Regras:**
  - Permitido **apenas** para notificações em estado `FAILED` pertencentes à aplicação autenticada.
  - Notificações em qualquer outro status retornam `409 Conflict` (RFC 9457).
  - Notificações inexistentes ou de outro tenant retornam `404 Not Found`.
  - A notificação volta ao status `QUEUED`, é republicada no broker e o histórico de auditoria anterior é integralmente preservado, continuando a contagem de tentativas (ex.: tentativa 4).

---

## Observabilidade e Métricas (Micrometer & Actuator)

### Endpoints Disponíveis

| Endpoint | Autenticação | Descrição |
|---|---|---|
| `GET /actuator/health` | **Público** | Reflete a saúde do serviço, validando conectividade com o banco de dados **MySQL** e o broker **RabbitMQ**. |
| `GET /actuator/info` | **Público** | Informações gerais do serviço. |
| `GET /actuator/metrics` | Requer `X-Admin-Token` | Lista métricas operacionais coletadas via Micrometer. |
| `GET /actuator/metrics/{name}` | Requer `X-Admin-Token` | Detalhes da métrica específica. |

> [!NOTE]
> **Decisão de Segurança:**
> Os endpoints `/actuator/health` e `/actuator/info` são públicos para viabilizar sondas de liveness/readiness de orquestradores (Kubernetes, AWS ALB, Docker Healthcheck).
> Já os endpoints de métricas (`/actuator/metrics/**`) são restritos pelo token administrativo (`X-Admin-Token`) ou rede interna. Essa proteção evita vazamento de métricas operacionais, volumetria de clientes e detalhes internos de infraestrutura para a internet aberta.

### Principais Métricas Coletadas

- `notifications.sent`: Contador de notificações enviadas com sucesso (tag `channel`).
- `notifications.failed`: Contador de falhas de envio (tag `channel`).
- `notifications.attempts`: Contador do número total de tentativas de entrega (tag `channel`).
- `notifications.send.duration`: Timer medindo a latência real de envio junto aos provedores (tag `channel`).

Exemplo de consulta de métricas:
```bash
curl -X GET http://localhost:8080/actuator/metrics/notifications.sent \
     -H "X-Admin-Token: {seu_admin_token}"
```

---

## Endpoints Principais da API

### Templates

- `POST /api/v1/templates` - Cria novo template de mensagem
- `GET  /api/v1/templates` - Lista templates (filtro opcional: `?channel=EMAIL`)
- `GET  /api/v1/templates/{id}` - Consulta template por ID
- `PUT  /api/v1/templates/{id}` - Altera assunto e conteúdo do template

### Notificações

- `POST /api/v1/notifications` - Enfileira notificação assíncrona (202 Accepted)
- `GET  /api/v1/notifications` - Histórico filtrado com paginação e isolamento multitenant
- `GET  /api/v1/notifications/{id}` - Consulta notificação e histórico de tentativas
- `POST /api/v1/notifications/{id}/retry` - Reprocessa notificação com status FAILED

