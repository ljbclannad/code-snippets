#!/bin/bash

# =============================================
# Test Script: AI Chat Service (End-to-End)
# Description: Test complete chat flow with context
# Service: ai-chat-service (port 8103)
# =============================================

set -e

echo "=========================================="
echo "Test 3: AI Chat Service (End-to-End)"
echo "=========================================="
echo ""

# Configuration
CUSTOMER_ID="1001"
GROUP_ID="health-assessment-group"
AI_CHAT_URL="http://localhost:8103/api/chat"

echo "[Test 3.1] Health Check"
echo "----------------------------------------"

# Health check
HEALTH_RESPONSE=$(curl -s -w "\n%{http_code}" http://localhost:8103/api/chat/health)
HTTP_CODE=$(echo "$HEALTH_RESPONSE" | tail -n 1)
HEALTH_BODY=$(echo "$HEALTH_RESPONSE" | sed '$d')

if [ "$HTTP_CODE" = "200" ]; then
    echo "✓ AI Chat Service is healthy"
    echo "  Response: $HEALTH_BODY"
else
    echo "✗ AI Chat Service health check failed (HTTP $HTTP_CODE)"
    exit 1
fi

echo ""
echo "[Test 3.2] Chat Request (with context)"
echo "----------------------------------------"

# Prepare chat request
CHAT_REQUEST=$(cat <<EOF
{
  "customerId": "$CUSTOMER_ID",
  "groupId": "$GROUP_ID",
  "message": "我有低盐饮食的要求，能推荐一些适合我的食谱吗？"
}
EOF
)

echo "Sending chat request..."
echo "Customer ID: $CUSTOMER_ID"
echo "Group ID: $GROUP_ID"
echo "Message: 我有低盐饮食的要求，能推荐一些适合我的食谱吗？"
echo ""

# Send chat request
CHAT_RESPONSE=$(curl -s -w "\n%{http_code}" \
  -X POST "$AI_CHAT_URL" \
  -H "Content-Type: application/json" \
  -d "$CHAT_REQUEST")

HTTP_CODE=$(echo "$CHAT_RESPONSE" | tail -n 1)
RESPONSE_BODY=$(echo "$CHAT_RESPONSE" | sed '$d')

echo "Response received (HTTP $HTTP_CODE):"
echo "$RESPONSE_BODY" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE_BODY"

# Validate response
if [ "$HTTP_CODE" = "200" ]; then
    SUCCESS=$(echo "$RESPONSE_BODY" | python3 -c "import sys, json; print(json.load(sys.stdin).get('success', False))" 2>/dev/null || echo "false")

    if [ "$SUCCESS" = "True" ]; then
        echo ""
        echo "✓ Test PASSED"

        # Extract key information
        AI_RESPONSE=$(echo "$RESPONSE_BODY" | python3 -c "import sys, json; data=json.load(sys.stdin); print(data.get('data', {}).get('aiResponse', 'N/A')[:200])" 2>/dev/null || echo "N/A")
        RESPONSE_TIME=$(echo "$RESPONSE_BODY" | python3 -c "import sys, json; data=json.load(sys.stdin); print(data.get('data', {}).get('responseTime', 'N/A'))" 2>/dev/null || echo "N/A")
        CONTEXT_USED=$(echo "$RESPONSE_BODY" | python3 -c "import sys, json; data=json.load(sys.stdin); ctx=data.get('data', {}).get('contextUsed', {}); print(f\"User: {len(ctx.get('userProfile', []))}, Memories: {len(ctx.get('memories', []))}\")" 2>/dev/null || echo "N/A")

        echo "  - AI Response: $AI_RESPONSE..."
        echo "  - Response Time: ${RESPONSE_TIME}ms"
        echo "  - Context Used: $CONTEXT_USED"
    else
        echo ""
        echo "✗ Test FAILED"
        ERROR_MSG=$(echo "$RESPONSE_BODY" | python3 -c "import sys, json; print(json.load(sys.stdin).get('message', 'Unknown error'))" 2>/dev/null || echo "Unknown error")
        echo "  - Error: $ERROR_MSG"
        exit 1
    fi
else
    echo ""
    echo "✗ Test FAILED (HTTP $HTTP_CODE)"
    exit 1
fi

echo ""
echo "[Test 3.3] Chat Request (general query)"
echo "----------------------------------------"

# Second chat request - general query
CHAT_REQUEST_2=$(cat <<EOF
{
  "customerId": "$CUSTOMER_ID",
  "groupId": "$GROUP_ID",
  "message": "今天天气怎么样？"
}
EOF
)

echo "Sending second chat request..."
echo "Message: 今天天气怎么样？"
echo ""

CHAT_RESPONSE_2=$(curl -s -w "\n%{http_code}" \
  -X POST "$AI_CHAT_URL" \
  -H "Content-Type: application/json" \
  -d "$CHAT_REQUEST_2")

HTTP_CODE_2=$(echo "$CHAT_RESPONSE_2" | tail -n 1)
RESPONSE_BODY_2=$(echo "$CHAT_RESPONSE_2" | sed '$d')

echo "Response received (HTTP $HTTP_CODE_2):"
echo "$RESPONSE_BODY_2" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE_BODY_2"

if [ "$HTTP_CODE_2" = "200" ]; then
    SUCCESS_2=$(echo "$RESPONSE_BODY_2" | python3 -c "import sys, json; print(json.load(sys.stdin).get('success', False))" 2>/dev/null || echo "false")

    if [ "$SUCCESS_2" = "True" ]; then
        echo ""
        echo "✓ Test PASSED"

        AI_RESPONSE_2=$(echo "$RESPONSE_BODY_2" | python3 -c "import sys, json; data=json.load(sys.stdin); print(data.get('data', {}).get('aiResponse', 'N/A')[:200])" 2>/dev/null || echo "N/A")
        RESPONSE_TIME_2=$(echo "$RESPONSE_BODY_2" | python3 -c "import sys, json; data=json.load(sys.stdin); print(data.get('data', {}).get('responseTime', 'N/A'))" 2>/dev/null || echo "N/A")

        echo "  - AI Response: $AI_RESPONSE_2..."
        echo "  - Response Time: ${RESPONSE_TIME_2}ms"
    else
        echo ""
        echo "✗ Test FAILED"
        ERROR_MSG_2=$(echo "$RESPONSE_BODY_2" | python3 -c "import sys, json; print(json.load(sys.stdin).get('message', 'Unknown error'))" 2>/dev/null || echo "Unknown error")
        echo "  - Error: $ERROR_MSG_2"
        exit 1
    fi
else
    echo ""
    echo "✗ Test FAILED (HTTP $HTTP_CODE_2)"
    exit 1
fi

echo ""
echo "=========================================="
echo "Test 3 Complete"
echo "=========================================="
echo ""
echo "Summary:"
echo "  ✓ Health check passed"
echo "  ✓ Context-aware chat passed"
echo "  ✓ General query chat passed"
