#!/bin/bash
set -euo pipefail

# 默认走匿名入口。验证登录态时，由目标环境的身份网关验证令牌并建立患者 principal。
headers=(-H "Content-Type: application/json")
if [[ -n "${CHAT_PATIENT_TOKEN:-}" ]]; then
  headers+=(-H "Authorization: Bearer ${CHAT_PATIENT_TOKEN}")
fi

curl --fail --silent --show-error "${CHAT_BASE_URL:-http://localhost:8100}/api/chat" \
  "${headers[@]}" \
  -d '{
    "groupId": "test-group-001",
    "message": "我想了解一下如何改善我的血压问题"
  }' | jq -e 'select(.success == true and (.conversationId | type == "string"))'
