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

- **Transações Curtas e I/O Desacoplado:** Chamadas externas de envio (SMTP/provedores) ocorrem rigorosamente fora de transações de banco de dados, eliminando lock e saturação de pools.
- **Formato de Erro Padronizado:** Erros de validação, conflito e falhas de negócio respondem via `ProblemDetail` (RFC 9457) sem expor stack traces.
- **Banco de Dados:** MySQL 8.4 (`utf8mb4`, collation `utf8mb4_unicode_ci`, datas em UTC `DATETIME(6)`), gerenciado via Flyway.

---

## Pré-requisitos

- **Java 21** (JDK 21)
- **Docker** e **Docker Compose**
- Maven (ou o wrapper `./mvnw` incluído no projeto)

---

## Execução Local (Desenvolvimento)

No ambiente local, o banco de dados MySQL e o emulador de e-mails **Mailpit** rodam via Docker Compose:

1. **Subir os serviços locais (MySQL e Mailpit):**
   ```bash
   docker compose up -d
   ```
   *Nota: O MySQL local expõe a porta `3309` no host (mapeada para `3306` interna) e o Mailpit expõe a porta `1025` (SMTP) e `8025` (Interface Web).*

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

## Endpoints Principais da API

### Templates

- `POST /api/v1/templates` - Cria novo template de mensagem
- `GET  /api/v1/templates` - Lista templates (filtro opcional: `?channel=EMAIL`)
- `GET  /api/v1/templates/{id}` - Consulta template por ID
- `PUT  /api/v1/templates/{id}` - Altera assunto e conteúdo do template

### Notificações

- `POST /api/v1/notifications` - Dispara notificação síncrona
- `GET  /api/v1/notifications/{id}` - Consulta notificação e status por ID
