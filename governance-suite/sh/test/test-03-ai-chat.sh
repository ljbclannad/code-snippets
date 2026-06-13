#!/bin/bash

echo "测试 3: AI 聊天服务（端到端）"
echo "============================="

curl -X POST http://localhost:8100/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "groupId": "test-group-001",
    "customerId": "test-customer-001",
    "message": "我想了解一下如何改善我的血压问题"
  }' | jq .

echo ""
echo "✓ 端到端测试完成"
