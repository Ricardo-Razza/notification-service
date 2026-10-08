#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
curl -fsS "$BASE_URL/v3/api-docs/admin" > "$TASK_ROOT/docs/openapi-admin.json"
echo 'Admin OpenAPI exported; enable SWAGGER_ENABLED in the local backend.'
