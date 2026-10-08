# Notification Service

[![CI](https://github.com/Ricardo-Razza/notification-service/actions/workflows/ci.yml/badge.svg)](https://github.com/Ricardo-Razza/notification-service/actions/workflows/ci.yml)

Operação: [runbook](docs/runbook.md), [implantação](docs/deploy.md), [esquema](docs/banco.md).

## Configuração e atualização das filas

Use apenas `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_RABBITMQ_HOST`,
`SPRING_RABBITMQ_PORT`, `SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD` e `ADMIN_TOKEN`.
Os aliases antigos `MAIL_HOST`, `MAIL_PORT`, `RABBITMQ_USER` e `APP_ADMIN_TOKEN` foram removidos.
`.env` é usado pelo Compose; para rodar o Java fora do Docker, exporte as variáveis no terminal.
`SMTP_CONNECTION_TIMEOUT_MS`, `SMTP_READ_TIMEOUT_MS`, `SMTP_WRITE_TIMEOUT_MS` controlam
os timeouts (5000/10000/10000 ms). `RABBITMQ_PREFETCH` tem padrão 5; shutdown aguarda até 30s.
O healthcheck não expõe detalhes; `/actuator/metrics` exige `X-Admin-Token`.
Sem `ADMIN_TOKEN`, `/api/v1/admin/**` fica desabilitado com 404.

As filas de retry são `notification.retry-1`, `notification.retry-2`, `notification.retry-3`.
Mudar `RABBITMQ_RETRY_1_TTL`, `RABBITMQ_RETRY_2_TTL` ou `RABBITMQ_RETRY_3_TTL` não altera uma
fila já existente: RabbitMQ retorna `PRECONDITION_FAILED` ao redeclarar argumentos diferentes.
Pare produtores, aguarde o esvaziamento das filas antigas e pare consumidores. Confirme a contagem
zero no management antes de excluir **somente** as filas de retry afetadas; reinicie o serviço
para recriá-las. Não exclua a fila principal, DLQ ou volumes. Na troca dos nomes antigos,
primeiro deixe `notification.retry.10s`, `notification.retry.30s`, `notification.retry.2m`
esvaziarem. Se não puder aguardar, mova as mensagens com Shovel para a fila principal antes
de remover as filas antigas. Faça isso em janela de manutenção e confirme o histórico depois.

Progresso do roteiro: [docs/execucao-parte-2.md](docs/execucao-parte-2.md).

## Limites, recuperação e retenção

As variáveis com seus padrões estão em .env.example e .env.prod.example. A janela de rate limit é
em memória, por aplicação e minuto UTC; uma instância é suportada. Para múltiplas instâncias,
use armazenamento compartilhado para o limite. O batch recebe `{ "items": [...] }`, até 100 itens,
com os campos do envio individual e idempotencyKey opcional por item. Retorna 200 com um resultado
created/replayed/failed por item. Toda a quantidade é reservada antes do batch; limite excedido retorna 429
com Retry-After, sem criar itens. Reusar uma chave com conteúdo diferente retorna 422.

O reconciliador recupera PENDING/QUEUED/PROCESSING antigos após commit; os limites de idade devem ser
maiores que os timeouts SMTP e a soma dos TTLs de retry. Consulte runbook.md para simular cada caso.
Em queda após SMTP aceitar mas antes de gravar SENT, pode haver duplicidade de entrega; o claim atômico
protege workers concorrentes, mas não fornece exatamente uma entrega entre SMTP e MySQL.

Dados de notificações finais (SENT, FAILED, CANCELED) são anonimizados 30 dias após criação:
variáveis removidas, destinatário mascarado. Status, canal, datas e motivos sanitizados permanecem.
Aos 90 dias após criação, são excluídas com as tentativas em cascata. Estados em andamento não são apagados.
RETENTION_ANONYMIZE_DAYS=0 ou RETENTION_DELETE_DAYS=0 desliga a etapa correspondente.
Backups têm política de retenção própria; configure também a expiração no bucket externo.

Logs locais usam texto; LOG_FORMAT=logstash habilita JSON em produção. X-Request-Id válido é propagado
na resposta e na mensagem de fila; IDs inadequados são substituídos por UUID. Não há persistência de
credenciais no Swagger. springdoc 3.0.3 foi mantido na linha 3.0.x exigida pelo roteiro;
[a documentação oficial](https://springdoc.org/) confirma o suporte da linha 3.x ao Spring Boot 4.

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

5. **Acessar o Swagger UI (Documentação & Teste Interativo):**
   Abra no navegador: `http://localhost:8080/swagger-ui/index.html`

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

## Agendamento e Scheduler de Notificações

O serviço suporta o agendamento de notificações para liberação futura e cancelamento sob demanda:

### 1. Envio Agendado (`POST /api/v1/notifications`)
- Envie o campo `scheduledAt` no formato ISO-8601 com offset (ex.: `"2026-10-05T10:00:00-03:00"`).
- O horário deve ser estritamente no futuro e dentro do limite configurável `app.scheduling.max-future-days` (padrão: 365 dias).
- A notificação é salva com status `SCHEDULED` em UTC e **não é publicada na fila imediatamente**. A resposta retorna `202 Accepted` exibindo o `scheduledAt`.

### 2. Cancelamento (`DELETE /api/v1/notifications/{id}`)
- Permite cancelar uma notificação agendada que ainda não foi liberada.
- Notificações com status `SCHEDULED` passam para `CANCELED` (`204 No Content`).
- Tentativas de cancelar notificações em outros status retornam `409 Conflict`.
- Notificações pertencentes a outro tenant retornam `404 Not Found`.

### 3. Mecanismo do Scheduler e Concorrência Segura
- O `@Scheduled` executa periodicamente a cada `app.scheduler.interval-ms` (padrão: 5000 ms).
- **Transação Curta e `SKIP LOCKED`:** Busca registros com `status = 'SCHEDULED' AND scheduled_at <= :now ORDER BY scheduled_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED`. Com isso, múltiplas instâncias da aplicação podem rodar em paralelo sem que duas instâncias processem a mesma notificação nem ocorram deadlocks.
- **Política de Atraso (`max-lateness`):** Se a notificação venceu há mais tempo que `app.scheduler.max-lateness-seconds` (padrão: 3600 segundos / 1h), ela não é enviada atrasada; seu status é marcado como `FAILED` e `last_error` registra detalhadamente que o limite de tolerância expirou.

### 4. Agendamentos Recorrentes (CRON)
- Gerencie envios recorrentes via `/api/v1/schedules`:
  - `POST /api/v1/schedules`: cria agendamento com expressão cron de 6 campos (formato Spring), fuso horário IANA (ex.: `America/Sao_Paulo`), template, destinatário, variáveis, e datas opcionais `startAt` e `endAt`.
  - `GET /api/v1/schedules`: lista agendamentos da aplicação com paginação.
  - `GET /api/v1/schedules/{id}`: consulta agendamento por ID.
  - `PUT /api/v1/schedules/{id}`: altera configuração e recalcula `next_run_at`.
  - `DELETE /api/v1/schedules/{id}`: remove o agendamento.
  - `POST /api/v1/schedules/{id}/pause`: pausa o agendamento (`active = false`).
  - `POST /api/v1/schedules/{id}/resume`: retoma o agendamento (`active = true`, recalculando `next_run_at` a partir de agora).
- **Variáveis Dinâmicas de Sistema:**
  - `{{today}}`: Data no fuso horário do agendamento (ex.: `2026-10-04`), resolvida dinamicamente no momento da ocorrência.
  - `{{now}}`: Timestamp ISO-8601 com offset no fuso horário do agendamento.
- **Tratamento de Ocorrências Perdidas (Serviço Fora do Ar):**
  - Controlado por `app.scheduler.missed-occurrence-policy` (padrão: `SKIP_AND_GENERATE_LATEST`).
  - Ao retomar após indisponibilidade com múltiplas ocorrências vencidas, o scheduler gera apenas a ocorrência mais recente, descarta as anteriores e registra o salto em log estruturado.
- **Idempotência e Concorrência:**
  - Cada ocorrência gera uma notificação com chave de idempotência determinística: `schedule-{scheduleId}-{instanteDaOcorrencia}`.
  - A restrição única `(application_id, idempotency_key)` e o bloqueio transacional com `SKIP LOCKED` asseguram que nenhuma ocorrência seja gerada em duplicidade, mesmo com múltiplas instâncias concorrentes.

### 5. Decisão Arquitetural: Falha na Publicação Pós-Commit
> [!NOTE]
> Em estrita conformidade com a arquitetura do projeto, **nenhuma operação de I/O de rede ou broker ocorre dentro de transações de banco de dados**.
> Portanto:
> 1. O scheduler bloqueia as notificações elegíveis via `SKIP LOCKED`, atualiza o status de `SCHEDULED` para `QUEUED` e comita a transação no banco.
> 2. Logo em seguida, fora da transação, publica cada notificação no RabbitMQ.
> 3. **Se a publicação no broker falhar** (ex.: falha transitória de rede ou RabbitMQ indisponível no momento):
>    - A falha é registrada em log em nível `ERROR`.
>    - A notificação permanece no banco de dados com status `QUEUED`.
>    - **Decisão:** Manter a notificação como `QUEUED` a deixa em estado totalmente seguro e recuperável. O status indica que a notificação foi aprovada pelo agendamento e aguarda consumo, permitindo que a rotina de reconciliação ou intervenção operacional reenvie a mensagem sem risco de perda de dados.

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
- `DELETE /api/v1/notifications/{id}` - Cancela notificação com status SCHEDULED

### Agendamentos Recorrentes (Schedules)

- `POST /api/v1/schedules` - Cria agendamento recorrente com cron e fuso horário
- `GET  /api/v1/schedules` - Lista agendamentos recorrentes com paginação
- `GET  /api/v1/schedules/{id}` - Consulta agendamento recorrente por ID
- `PUT  /api/v1/schedules/{id}` - Atualiza configurações do agendamento
- `DELETE /api/v1/schedules/{id}` - Remove o agendamento recorrente
- `POST /api/v1/schedules/{id}/pause` - Pausa agendamento recorrente
- `POST /api/v1/schedules/{id}/resume` - Retoma agendamento recorrente recalculando próximo disparo


## Painel administrativo — Parte 2

O repositório irmão notification-console consome somente /api/admin/**, com sessão HttpOnly e CSRF.
Configure ADMIN_EMAIL/ADMIN_PASSWORD no primeiro bootstrap e remova a senha depois da criação.
ADMIN_COOKIE_SECURE=true é obrigatório atrás do HTTPS em produção. ADMIN_TOKEN continua separado,
para /api/v1/admin/** e métricas; o painel não usa esse token nem chaves de aplicação.

O Compose de produção inclui console e proxy. Para validar a integração local com Mailpit:

```sh
docker build -t notification-service:part2 .
docker build -t notification-console:part2 ../notification-console
docker compose -f docker-compose.smoke.yml up -d --wait
```

Painel de testes: http://127.0.0.1:14201; Mailpit: http://127.0.0.1:18025.
As credenciais dessa stack são fictícias e exclusivas dos testes locais.
Consulte [execução e validação](docs/execucao-parte-2.md), [auditoria](docs/estado-do-projeto.md),
[operação](docs/runbook.md) e [implantação](docs/deploy.md).
