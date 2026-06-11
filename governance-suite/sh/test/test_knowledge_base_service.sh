#!/bin/bash
# Knowledge Base Service API 测试脚本

set -euo pipefail

BASE_URL="http://localhost:8103"
TEST_QUERY="高血压的治疗方案"

echo "======================================"
echo "Knowledge Base Service API 测试"
echo "======================================"
echo ""

# 1. 创建知识库
echo "1. 创建知识库"
KB_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/kb" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "医学知识库",
    "description": "包含常见疾病和治疗方案",
    "chunkSize": 500,
    "chunkOverlap": 50,
    "semanticThreshold": 0.7,
    "createdBy": "test_user"
  }')

echo "$KB_RESPONSE" | jq '.'
KB_ID=$(echo "$KB_RESPONSE" | jq -r '.data.kbId')
if [ -z "$KB_ID" ] || [ "$KB_ID" = "null" ]; then
    echo "创建知识库失败"
    exit 1
fi
echo "知识库ID: $KB_ID"
echo ""

# 2. 查询知识库详情
echo "2. 查询知识库详情"
curl -s -X GET "${BASE_URL}/api/kb/${KB_ID}" | jq '.'
echo ""

# 3. 上传测试文档 (需要准备一个测试文档)
if [ -f "test_document.txt" ]; then
    echo "3. 上传文档"
    DOC_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/kb/${KB_ID}/documents" \
      -F "file=@test_document.txt" \
      -F "title=高血压治疗指南" \
      -F "tags=高血压,慢病,治疗" \
      -F "businessDomain=medical" \
      -F "source=manual_upload" \
      -F "applicableScope=doctor_assistant" \
      -F "trustLevel=HIGH" \
      -F "createdBy=test_user")

    echo "$DOC_RESPONSE" | jq '.'
    DOC_ID=$(echo "$DOC_RESPONSE" | jq -r '.data.docId')
    if [ -z "$DOC_ID" ] || [ "$DOC_ID" = "null" ]; then
        echo "文档上传失败"
        exit 1
    fi
    echo "文档ID: $DOC_ID"
    echo ""

    # 等待文档处理
    echo "等待文档处理..."
    sleep 3

    # 4. 查询文档状态
    echo "4. 查询文档状态"
    DOC_STATUS_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/kb/${KB_ID}/documents/${DOC_ID}")
    echo "$DOC_STATUS_RESPONSE" | jq '.'
    DOC_STATUS=$(echo "$DOC_STATUS_RESPONSE" | jq -r '.data.status')
    if [ "$DOC_STATUS" != "ACTIVE" ]; then
        echo "文档处理未完成，当前状态: $DOC_STATUS"
        exit 1
    fi
    echo ""

    # 5. 语义检索
    echo "5. 语义检索"
    SEARCH_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/kb/${KB_ID}/search" \
      -H "Content-Type: application/json" \
      -d '{
        "query": "'"${TEST_QUERY}"'",
        "topK": 5,
        "similarityThreshold": 0.1,
        "rerank": false
      }')
    echo "$SEARCH_RESPONSE" | jq '.'
    RESULT_COUNT=$(echo "$SEARCH_RESPONSE" | jq -r '.data.totalCount')
    if [ "${RESULT_COUNT}" -lt 1 ]; then
        echo "语义检索未返回结果"
        exit 1
    fi
    echo ""
else
    echo "3. 跳过文档上传测试 (test_document.txt 不存在)"
    echo ""
fi

# 6. 列出所有文档
echo "6. 列出知识库下的所有文档"
curl -s -X GET "${BASE_URL}/api/kb/${KB_ID}/documents" | jq '.'
echo ""

echo "======================================"
echo "测试完成"
echo "======================================"
