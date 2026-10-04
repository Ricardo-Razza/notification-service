#!/usr/bin/env bash
# ==============================================================================
# Script de Backup Automatizado do MySQL - Notification Service
# ==============================================================================
# Uso manual:
#   ./scripts/backup.sh
#
# Configuração recomendada no crontab da VPS (diariamente às 02:00 AM):
#   0 2 * * * /caminho/para/notification-service/scripts/backup.sh >> /var/log/notification-backup.log 2>&1
# ==============================================================================

set -euo pipefail

# Diretório base do script
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BACKUP_DIR="${PROJECT_ROOT}/backups"
ENV_FILE="${PROJECT_ROOT}/.env.prod"

# Carrega variáveis de ambiente se o arquivo existir
if [ -f "$ENV_FILE" ]; then
    # shellcheck disable=SC1090
    set -a
    source "$ENV_FILE"
    set +a
fi

DB_NAME="${DB_NAME:-notification}"
DB_USER="${DB_USER:-notification_user}"
DB_PASSWORD="${DB_PASSWORD:-}"
CONTAINER_NAME="notification-mysql-prod"
TIMESTAMP="$(date +'%Y%m%d_%H%M%S')"
BACKUP_FILE="${BACKUP_DIR}/notification_${TIMESTAMP}.sql.gz"
RETENTION_DAYS=7

mkdir -p "$BACKUP_DIR"

echo "[$(date -Iseconds)] Iniciando backup do banco de dados '${DB_NAME}'..."

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "[$(date -Iseconds)] ERRO: Container '${CONTAINER_NAME}' não está em execução!" >&2
    exit 1
fi

# Executa mysqldump dentro do container com compressão gzip
docker exec "$CONTAINER_NAME" mysqldump \
    -u "$DB_USER" \
    "-p$DB_PASSWORD" \
    --single-transaction \
    --quick \
    --databases "$DB_NAME" | gzip > "$BACKUP_FILE"

BACKUP_SIZE="$(du -h "$BACKUP_FILE" | cut -f1)"
echo "[$(date -Iseconds)] Backup concluído com sucesso: ${BACKUP_FILE} (${BACKUP_SIZE})"

# Rotação: remove arquivos com mais de 7 dias
echo "[$(date -Iseconds)] Executando rotação de backups (retenção: ${RETENTION_DAYS} dias)..."
find "$BACKUP_DIR" -type f -name "notification_*.sql.gz" -mtime +"$RETENTION_DAYS" -exec rm -f {} \;

echo "[$(date -Iseconds)] Processo finalizado com sucesso."
