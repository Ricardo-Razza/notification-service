#!/usr/bin/env bash
set -euo pipefail
umask 077
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$TASK_ROOT"
ENV_FILE="${ENV_FILE:-.env.prod}"
if [[ -f "$ENV_FILE" ]]; then set -a; source "$ENV_FILE"; set +a; fi
BACKUP_DIR="${BACKUP_DIR:-$TASK_ROOT/backups}"
mkdir -p "$BACKUP_DIR"
BACKUP_DIR="$(cd "$BACKUP_DIR" && pwd)"
[[ "$BACKUP_DIR" == "$TASK_ROOT/"* ]] || { echo 'Backup directory must be inside the project' >&2; exit 1; }
UPLOAD="${BACKUP_UPLOAD_ENABLED:-true}"
if [[ "$UPLOAD" == true ]]; then : "${BACKUP_REMOTE:?Configure an external rclone destination}"; command -v rclone >/dev/null; fi
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "${COMPOSE_FILE:-docker-compose.prod.yml}")
STAMP="$(date -u +%Y%m%d_%H%M%S)"
FILE="$BACKUP_DIR/notification_$STAMP.sql.gz"
TEMP="$(mktemp "$BACKUP_DIR/.dump.XXXXXX")"
trap 'rm -f -- "$TEMP"' EXIT
# Credenciais por ambiente, sem senha na linha de comando.
"${COMPOSE[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -u "$MYSQL_USER" --single-transaction --quick --no-tablespaces --skip-extended-insert --hex-blob "$MYSQL_DATABASE"' > "$TEMP"
awk '/^CREATE TABLE `/ { split($0,a,"`"); counts[a[2]]=0 } /^INSERT INTO `/ { split($0,a,"`"); counts[a[2]]++ } END { for(t in counts) print t " " counts[t] }' "$TEMP" | sort > "$FILE.counts"
gzip -c "$TEMP" > "$FILE"
(cd "$BACKUP_DIR" && sha256sum "$(basename "$FILE")" > "$(basename "$FILE").sha256")
if [[ "$UPLOAD" == true ]]; then
    for artifact in "$FILE" "$FILE.counts" "$FILE.sha256"; do rclone copy "$artifact" "$BACKUP_REMOTE"; done
fi
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'notification_[0-9]*.sql.gz*' -mtime +"${BACKUP_LOCAL_DAYS:-7}" -delete
echo "Backup completed: $(basename "$FILE") (external upload=$UPLOAD)"
