#!/usr/bin/env bash
set -euo pipefail
umask 077
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$TASK_ROOT"
ENV_FILE="${ENV_FILE:-.env.prod}"
if [[ -f "$ENV_FILE" ]]; then set -a; source "$ENV_FILE"; set +a; fi
BACKUP_DIR="${BACKUP_DIR:-$TASK_ROOT/backups}"
if [[ -z "${BACKUP_FILE:-}" ]]; then BACKUP_FILE="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'notification_*.sql.gz' | sort | tail -n 1)"; fi
[[ -n "$BACKUP_FILE" && -f "$BACKUP_FILE" && -f "$BACKUP_FILE.counts" && -f "$BACKUP_FILE.sha256" ]] || { echo 'Backup, checksum and counts are required' >&2; exit 1; }
BACKUP_FILE="$(cd "$(dirname "$BACKUP_FILE")" && pwd)/$(basename "$BACKUP_FILE")"
(cd "$(dirname "$BACKUP_FILE")" && sha256sum -c "$(basename "$BACKUP_FILE").sha256")
TEMP_DB="notification_restore_$(date -u +%s)_${RANDOM}"
[[ "$TEMP_DB" =~ ^notification_restore_[0-9]+_[0-9]+$ ]] || exit 1
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "${COMPOSE_FILE:-docker-compose.prod.yml}")
sql() { "${COMPOSE[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B'; }
CREATED=false
cleanup() { if [[ "$CREATED" == true ]]; then printf 'DROP DATABASE IF EXISTS `%s`;\n' "$TEMP_DB" | sql >/dev/null; fi; }
trap cleanup EXIT
printf 'CREATE DATABASE `%s` CHARACTER SET utf8mb4;\n' "$TEMP_DB" | sql
CREATED=true
{ printf 'USE `%s`;\n' "$TEMP_DB"; gzip -dc "$BACKUP_FILE"; } | sql
while read -r table expected; do
    [[ "$table" =~ ^[a-z0-9_]+$ && "$expected" =~ ^[0-9]+$ ]] || exit 1
    actual="$(printf 'SELECT COUNT(*) FROM `%s`.`%s`;\n' "$TEMP_DB" "$table" | sql)"
    [[ "$actual" == "$expected" ]] || { echo "Restore count mismatch: $table" >&2; exit 1; }
done < "$BACKUP_FILE.counts"
echo 'Restore checksum, schema and table counts passed; temporary database removed on exit.'
