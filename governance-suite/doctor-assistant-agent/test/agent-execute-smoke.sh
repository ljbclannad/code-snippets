#!/bin/bash

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8088/api/agent}"

payload='{
  "goal": "分析患者张三的健康状况，包括体重变化趋势和最近的情绪状态",
  "customerId": "C001",
  "doctorId": "D001"
}'

response="$(curl -sS -w '\n%{http_code}' \
  -X POST "$BASE_URL/execute" \
  -H "Content-Type: application/json" \
  -d "$payload")"

http_code="$(printf '%s\n' "$response" | tail -n 1)"
body="$(printf '%s\n' "$response" | sed '$d')"

echo "HTTP $http_code"
printf '%s\n' "$body"

if [ "$http_code" != "200" ]; then
  echo "Expected HTTP 200, got HTTP $http_code" >&2
  exit 1
fi

if ! printf '%s\n' "$body" | grep -q '"sessionId"'; then
  echo "Response does not contain sessionId" >&2
  exit 1
fi

if ! printf '%s\n' "$body" | grep -Eq '"status":"(SUCCESS|PENDING_APPROVAL)"'; then
  echo "Response status is not SUCCESS or PENDING_APPROVAL" >&2
  exit 1
fi
