#!/bin/bash

# 测试 Gateway 限流功能
# 使用方式：./test-ratelimit.sh

GATEWAY_URL="http://localhost:8080"
USER_ID=1001
SCHEDULE_ID=1

echo "=========================================="
echo "Gateway 限流功能测试"
echo "=========================================="

# 前置条件：需要先登录获取 token
echo ""
echo "1. 获取认证 token"
echo "------------------------------------------"

# 注册用户（如果不存在）
curl -s -X POST "${GATEWAY_URL}/api/user/register" \
  -H "Content-Type: application/json" \
  -d "{\"username\": \"testuser${USER_ID}\", \"password\": \"password123\"}" > /dev/null

# 登录获取 token
LOGIN_RESPONSE=$(curl -s -X POST "${GATEWAY_URL}/api/user/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\": \"testuser${USER_ID}\", \"password\": \"password123\"}")

TOKEN=$(echo $LOGIN_RESPONSE | grep -o '"data":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
  echo "错误：无法获取 token"
  echo "响应: ${LOGIN_RESPONSE}"
  exit 1
fi

echo "Token 获取成功: ${TOKEN:0:20}..."

# 准备 Redis 数据（模拟排班和热门科室）
echo ""
echo "2. 准备测试数据"
echo "------------------------------------------"

# 设置排班信息（scheduleId=1, deptId=101）
redis-cli -h localhost -p 36379 SET "appointment:schedule:1" \
  '{"scheduleId":1,"deptId":101,"doctorId":1,"scheduleDate":"2026-05-28","timeSlot":"AM","quota":100}' > /dev/null

# 设置科室为热门（deptId=101）
redis-cli -h localhost -p 36379 SET "appointment:dept:hot:101" "1" > /dev/null

# 预加载库存到 Redis
redis-cli -h localhost -p 36379 SET "appointment:stock:1" "100" > /dev/null

echo "测试数据准备完成"

echo ""
echo "3. 测试正常请求通过"
echo "------------------------------------------"

RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" -X POST "${GATEWAY_URL}/api/appointment/grab" \
  -H "Content-Type: application/json" \
  -H "Authorization: ${TOKEN}" \
  -d "{\"scheduleId\": ${SCHEDULE_ID}}")

HTTP_CODE=$(echo "$RESPONSE" | grep "HTTP_CODE" | cut -d: -f2)
BODY=$(echo "$RESPONSE" | sed '/HTTP_CODE/d')

echo "HTTP 状态码: ${HTTP_CODE}"
echo "响应体: ${BODY}"

if [ "$HTTP_CODE" != "200" ]; then
  echo "警告：正常请求未通过（可能是业务逻辑限制）"
fi

echo ""
echo "4. 测试 userId 限流（快速连续请求）"
echo "------------------------------------------"

SUCCESS_COUNT=0
RATE_LIMIT_COUNT=0

for i in {1..10}; do
  RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" -X POST "${GATEWAY_URL}/api/appointment/grab" \
    -H "Content-Type: application/json" \
    -H "Authorization: ${TOKEN}" \
    -d "{\"scheduleId\": ${SCHEDULE_ID}}")

  HTTP_CODE=$(echo "$RESPONSE" | grep "HTTP_CODE" | cut -d: -f2)

  if [ "$HTTP_CODE" == "429" ]; then
    RATE_LIMIT_COUNT=$((RATE_LIMIT_COUNT + 1))
  else
    SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
  fi
done

echo "成功请求数: ${SUCCESS_COUNT}"
echo "限流请求数: ${RATE_LIMIT_COUNT}"

if [ "$RATE_LIMIT_COUNT" -gt 0 ]; then
  echo "✅ userId 限流生效"
else
  echo "❌ userId 限流未生效"
fi

# 等待限流窗口重置
sleep 2

echo ""
echo "5. 测试 scheduleId 限流（并发请求）"
echo "------------------------------------------"

# 使用不同的 userId 避免 userId 限流
# 创建多个用户并发请求同一个 scheduleId

CONCURRENT_USERS=30
RATE_LIMIT_COUNT=0
TEMP_FILE=$(mktemp)

for i in $(seq 1 $CONCURRENT_USERS); do
  USER_ID=$((1000 + i))

  # 注册并登录
  curl -s -X POST "${GATEWAY_URL}/api/user/register" \
    -H "Content-Type: application/json" \
    -d "{\"username\": \"testuser${USER_ID}\", \"password\": \"password123\"}" > /dev/null

  LOGIN_RESP=$(curl -s -X POST "${GATEWAY_URL}/api/user/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\": \"testuser${USER_ID}\", \"password\": \"password123\"}")

  USER_TOKEN=$(echo $LOGIN_RESP | grep -o '"data":"[^"]*"' | cut -d'"' -f4)

  # 并发请求（后台执行）
  (
    RESP=$(curl -s -w "\nHTTP_CODE:%{http_code}" -X POST "${GATEWAY_URL}/api/appointment/grab" \
      -H "Content-Type: application/json" \
      -H "Authorization: ${USER_TOKEN}" \
      -d "{\"scheduleId\": ${SCHEDULE_ID}}")

    HTTP_CODE=$(echo "$RESP" | grep "HTTP_CODE" | cut -d: -f2)

    if [ "$HTTP_CODE" == "429" ]; then
      echo "429" >> "$TEMP_FILE"
    fi
  ) &
done

# 等待所有后台任务完成
wait

RATE_LIMIT_COUNT=$(wc -l < "$TEMP_FILE" 2>/dev/null || echo 0)
rm -f "$TEMP_FILE"

echo "并发用户数: ${CONCURRENT_USERS}"
echo "触发限流数: ${RATE_LIMIT_COUNT}"

if [ "$RATE_LIMIT_COUNT" -gt 0 ]; then
  echo "✅ scheduleId 限流生效"
else
  echo "⚠️  scheduleId 限流未明显触发（可能需要更高并发）"
fi

echo ""
echo "6. 验证 429 响应格式"
echo "------------------------------------------"

# 快速请求触发限流
for i in {1..10}; do
  curl -s -X POST "${GATEWAY_URL}/api/appointment/grab" \
    -H "Content-Type: application/json" \
    -H "Authorization: ${TOKEN}" \
    -d "{\"scheduleId\": ${SCHEDULE_ID}}" > /dev/null
done

RESPONSE=$(curl -s -X POST "${GATEWAY_URL}/api/appointment/grab" \
  -H "Content-Type: application/json" \
  -H "Authorization: ${TOKEN}" \
  -d "{\"scheduleId\": ${SCHEDULE_ID}}")

echo "429 响应示例:"
echo "${RESPONSE}" | python3 -m json.tool 2>/dev/null || echo "${RESPONSE}"

# 检查响应格式
if echo "${RESPONSE}" | grep -q '"success":false' && \
   echo "${RESPONSE}" | grep -q '"code":"RATE_LIMIT_EXCEEDED"'; then
  echo "✅ 429 响应格式正确"
else
  echo "❌ 429 响应格式不正确"
fi

echo ""
echo "=========================================="
echo "测试完成"
echo "=========================================="
