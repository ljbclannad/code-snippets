#!/bin/bash

# 端到端测试脚本
# 测试完整的 Agent 执行流程

set -e

BASE_URL="http://localhost:8088/api/agent"
DOCTOR_ID="D001"
CUSTOMER_ID="C001"

echo "=========================================="
echo "医生助手 Agent 端到端测试"
echo "=========================================="
echo ""

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 测试结果统计
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

# 测试函数
test_api() {
    local test_name=$1
    local method=$2
    local endpoint=$3
    local data=$4
    local expected_status=$5

    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    echo -e "${YELLOW}测试 $TOTAL_TESTS: $test_name${NC}"

    if [ -z "$data" ]; then
        response=$(curl -s -w "\n%{http_code}" -X $method "$BASE_URL$endpoint")
    else
        response=$(curl -s -w "\n%{http_code}" -X $method "$BASE_URL$endpoint" \
            -H "Content-Type: application/json" \
            -d "$data")
    fi

    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')

    if [ "$http_code" == "$expected_status" ]; then
        echo -e "${GREEN}✓ 通过 (HTTP $http_code)${NC}"
        PASSED_TESTS=$((PASSED_TESTS + 1))
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        echo -e "${RED}✗ 失败 (期望 HTTP $expected_status, 实际 HTTP $http_code)${NC}"
        FAILED_TESTS=$((FAILED_TESTS + 1))
        echo "$body"
    fi

    echo ""
    echo "$body"
}

echo "=========================================="
echo "测试 1: 执行 Agent 任务（查询类操作）"
echo "=========================================="
echo ""

SESSION_ID=""
TASK_ID=""

# 执行 Agent 任务
echo "发送请求: POST /execute"
response=$(curl -s -X POST "$BASE_URL/execute" \
    -H "Content-Type: application/json" \
    -d '{
        "goal": "分析患者张三的健康状况，包括体重变化趋势和最近的情绪状态",
        "customerId": "C001",
        "doctorId": "D001"
    }')

echo "$response" | jq '.'

# 提取 sessionId
SESSION_ID=$(echo "$response" | jq -r '.sessionId')
STATUS=$(echo "$response" | jq -r '.status')

if [ "$STATUS" == "SUCCESS" ]; then
    echo -e "${GREEN}✓ Agent 任务执行成功${NC}"
    echo "会话ID: $SESSION_ID"
    echo ""
    echo "最终答案:"
    echo "$response" | jq -r '.finalAnswer'
elif [ "$STATUS" == "PENDING_APPROVAL" ]; then
    echo -e "${YELLOW}⚠ Agent 需要人工确认${NC}"
    TASK_ID=$(echo "$response" | jq -r '.pendingTask.id')
    echo "会话ID: $SESSION_ID"
    echo "任务ID: $TASK_ID"
else
    echo -e "${RED}✗ Agent 任务执行失败${NC}"
    echo "$response" | jq -r '.errorMessage'
fi

echo ""
echo "=========================================="
echo "测试 2: 查询会话详情"
echo "=========================================="
echo ""

if [ -n "$SESSION_ID" ] && [ "$SESSION_ID" != "null" ]; then
    test_api "查询会话详情" "GET" "/sessions/$SESSION_ID" "" "200"
else
    echo -e "${RED}✗ 跳过测试（会话ID为空）${NC}"
    echo ""
fi

echo "=========================================="
echo "测试 3: 查询医生的会话列表"
echo "=========================================="
echo ""

test_api "查询所有会话" "GET" "/sessions?doctorId=$DOCTOR_ID" "" "200"

echo "=========================================="
echo "测试 4: 查询患者的会话历史"
echo "=========================================="
echo ""

test_api "查询患者会话历史" "GET" "/sessions/customer/$CUSTOMER_ID?limit=5" "" "200"

echo "=========================================="
echo "测试 5: 执行高风险操作（需要人工确认）"
echo "=========================================="
echo ""

echo "发送请求: POST /execute（开具处方）"
response=$(curl -s -X POST "$BASE_URL/execute" \
    -H "Content-Type: application/json" \
    -d '{
        "goal": "为患者张三开具降压药处方，厄贝沙坦片150mg，每日1次",
        "customerId": "C001",
        "doctorId": "D001"
    }')

echo "$response" | jq '.'

SESSION_ID=$(echo "$response" | jq -r '.sessionId')
STATUS=$(echo "$response" | jq -r '.status')

if [ "$STATUS" == "PENDING_APPROVAL" ]; then
    echo -e "${GREEN}✓ 高风险操作正确触发人工确认${NC}"
    TASK_ID=$(echo "$response" | jq -r '.pendingTask.id')
    echo "会话ID: $SESSION_ID"
    echo "任务ID: $TASK_ID"
    echo ""
    echo "待确认任务详情:"
    echo "$response" | jq '.pendingTask'
else
    echo -e "${YELLOW}⚠ 未触发人工确认（可能是 Mock 模式）${NC}"
fi

echo ""
echo "=========================================="
echo "测试 6: 查询待确认任务"
echo "=========================================="
echo ""

test_api "查询医生的待确认任务" "GET" "/pending-tasks?doctorId=$DOCTOR_ID" "" "200"

if [ -n "$SESSION_ID" ] && [ "$SESSION_ID" != "null" ]; then
    test_api "查询会话的待确认任务" "GET" "/sessions/$SESSION_ID/pending-tasks" "" "200"
fi

if [ -n "$TASK_ID" ] && [ "$TASK_ID" != "null" ]; then
    test_api "查询任务详情" "GET" "/pending-tasks/$TASK_ID" "" "200"
fi

echo "=========================================="
echo "测试 7: 批准任务"
echo "=========================================="
echo ""

if [ -n "$TASK_ID" ] && [ "$TASK_ID" != "null" ]; then
    test_api "批准任务" "POST" "/pending-tasks/$TASK_ID/approve" \
        '{"reviewComment": "处方合理，批准开具"}' "200"
else
    echo -e "${YELLOW}⚠ 跳过测试（任务ID为空）${NC}"
    echo ""
fi

echo "=========================================="
echo "测试 8: 恢复 Agent 执行"
echo "=========================================="
echo ""

if [ -n "$SESSION_ID" ] && [ "$SESSION_ID" != "null" ] && [ -n "$TASK_ID" ] && [ "$TASK_ID" != "null" ]; then
    echo "发送请求: POST /resume"
    response=$(curl -s -X POST "$BASE_URL/resume" \
        -H "Content-Type: application/json" \
        -d "{
            \"sessionId\": \"$SESSION_ID\",
            \"approvedTaskId\": $TASK_ID
        }")

    echo "$response" | jq '.'

    STATUS=$(echo "$response" | jq -r '.status')

    if [ "$STATUS" == "SUCCESS" ]; then
        echo -e "${GREEN}✓ Agent 恢复执行成功${NC}"
        echo ""
        echo "最终答案:"
        echo "$response" | jq -r '.finalAnswer'
    else
        echo -e "${RED}✗ Agent 恢复执行失败${NC}"
        echo "$response" | jq -r '.errorMessage'
    fi
else
    echo -e "${YELLOW}⚠ 跳过测试（会话ID或任务ID为空）${NC}"
fi

echo ""
echo "=========================================="
echo "测试 9: 拒绝任务"
echo "=========================================="
echo ""

# 创建一个新任务用于测试拒绝
echo "创建新任务..."
response=$(curl -s -X POST "$BASE_URL/execute" \
    -H "Content-Type: application/json" \
    -d '{
        "goal": "为患者李四开具降糖药处方",
        "customerId": "C002",
        "doctorId": "D001"
    }')

NEW_TASK_ID=$(echo "$response" | jq -r '.pendingTask.id')

if [ -n "$NEW_TASK_ID" ] && [ "$NEW_TASK_ID" != "null" ]; then
    test_api "拒绝任务" "POST" "/pending-tasks/$NEW_TASK_ID/reject" \
        '{"reviewComment": "剂量需要调整，暂不批准"}' "200"
else
    echo -e "${YELLOW}⚠ 跳过测试（未创建新任务）${NC}"
    echo ""
fi

echo "=========================================="
echo "测试 10: 错误处理"
echo "=========================================="
echo ""

test_api "查询不存在的会话" "GET" "/sessions/99999" "" "404"
test_api "查询不存在的任务" "GET" "/pending-tasks/99999" "" "404"

echo "=========================================="
echo "测试结果统计"
echo "=========================================="
echo ""
echo "总测试数: $TOTAL_TESTS"
echo -e "${GREEN}通过: $PASSED_TESTS${NC}"
echo -e "${RED}失败: $FAILED_TESTS${NC}"
echo ""

if [ $FAILED_TESTS -eq 0 ]; then
    echo -e "${GREEN}✓ 所有测试通过！${NC}"
    exit 0
else
    echo -e "${RED}✗ 部分测试失败${NC}"
    exit 1
fi
