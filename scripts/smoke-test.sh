#!/usr/bin/env bash
set -euo pipefail
command -v jq >/dev/null
: "${ADMIN_TOKEN:?ADMIN_TOKEN is required}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
RECIPIENT="${SMOKE_RECIPIENT:-smoke@example.invalid}"
NAME="smoke-$(date -u +%s)-$RANDOM"
created="$(curl -fsS -X POST "$BASE_URL/api/v1/admin/applications" -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' --data "$(jq -nc --arg name "$NAME" '{name:$name}')")"
api_key="$(jq -er '.apiKey' <<< "$created")"
unset created
code="SMOKE_${RANDOM}_${RANDOM}"
curl -fsS -X POST "$BASE_URL/api/v1/templates" -H "X-API-Key: $api_key" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg code "$code" '{code:$code,channel:"EMAIL",subject:"Smoke test",content:"Hello {{name}}"}')" >/dev/null
response="$(curl -fsS -X POST "$BASE_URL/api/v1/notifications" -H "X-API-Key: $api_key" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg recipient "$RECIPIENT" --arg code "$code" '{recipient:$recipient,channel:"EMAIL",template:$code,variables:{name:"Smoke"}}')")"
id="$(jq -er '.id' <<< "$response")"
for ((i=0;i<60;i++)); do
    state="$(curl -fsS "$BASE_URL/api/v1/notifications/$id" -H "X-API-Key: $api_key" | jq -er '.status')"
    if [[ "$state" == SENT ]]; then unset api_key; echo "Smoke test passed: $id SENT"; exit 0; fi
    [[ "$state" != FAILED ]] || { echo "Smoke test failed: $id FAILED" >&2; exit 1; }
    sleep 1
done
echo "Smoke test timed out: $id" >&2
exit 1
