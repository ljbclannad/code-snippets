#!/bin/bash

echo "=== 抢号流程测试 ==="

# 获取排班 ID
SCHEDULE_ID=$(docker exec gov-redis redis-cli KEYS "appointment:stock:*" | head -1 | sed 's/appointment:stock://')
echo "测试排班 ID: $SCHEDULE_ID"

# 记录初始库存
INITIAL_STOCK=$(docker exec gov-redis redis-cli GET "appointment:stock:$SCHEDULE_ID")
echo "初始库存: $INITIAL_STOCK"

echo ""
echo "1. 正常抢号"
RESPONSE=$(curl -s -X POST http://localhost:8089/api/appointment/grab \
  -H "X-User-Id: 1001" \
  -H "Idempotency-Key: test-grab-$(date +%s)-001" \
  -H "Content-Type: application/json" \
  -d "{\"scheduleId\": $SCHEDULE_ID}")
echo "$RESPONSE" | jq '.success, .message'

echo ""
echo "2. 验证 Redis 库存扣减"
CURRENT_STOCK=$(docker exec gov-redis redis-cli GET "appointment:stock:$SCHEDULE_ID")
echo "当前库存: $CURRENT_STOCK (应该比初始库存少 1)"

echo ""
echo "3. 验证 Stream 消息"
STREAM_LEN=$(docker exec gov-redis redis-cli XLEN "appointment:stream:orders")
echo "Stream 消息数: $STREAM_LEN"

echo ""
echo "4. 等待异步落库（2秒）"
sleep 2

echo ""
echo "5. 验证订单已创建"
mysql -h127.0.0.1 -uroot -proot order_db -e "SELECT COUNT(*) AS count FROM t_appointment_order WHERE schedule_id = $SCHEDULE_ID;" 2>&1 | grep -v "Using a password"

echo ""
echo "6. 验证幂等表"
mysql -h127.0.0.1 -uroot -proot order_db -e "SELECT COUNT(*) AS count FROM t_appointment_idempotency WHERE schedule_id = $SCHEDULE_ID;" 2>&1 | grep -v "Using a password"

echo ""
echo "7. 重复抢号（相同用户）"
curl -s -X POST http://localhost:8089/api/appointment/grab \
  -H "X-User-Id: 1001" \
  -H "Idempotency-Key: test-grab-$(date +%s)-002" \
  -H "Content-Type: application/json" \
  -d "{\"scheduleId\": $SCHEDULE_ID}" | jq '.success, .message'

echo ""
echo "8. 测试幂等键重复"
curl -s -X POST http://localhost:8089/api/appointment/grab \
  -H "X-User-Id: 1002" \
  -H "Idempotency-Key: test-grab-$(date +%s)-001" \
  -H "Content-Type: application/json" \
  -d "{\"scheduleId\": $SCHEDULE_ID}" | jq '.success, .message'

echo ""
echo "9. 测试库存耗尽"
# 保存当前库存
SAVED_STOCK=$(docker exec gov-redis redis-cli GET "appointment:stock:$SCHEDULE_ID")
# 设置库存为 0
docker exec gov-redis redis-cli SET "appointment:stock:$SCHEDULE_ID" "0" > /dev/null
curl -s -X POST http://localhost:8089/api/appointment/grab \
  -H "X-User-Id: 1003" \
  -H "Idempotency-Key: test-grab-$(date +%s)-003" \
  -H "Content-Type: application/json" \
  -d "{\"scheduleId\": $SCHEDULE_ID}" | jq '.success, .message'
# 恢复库存
docker exec gov-redis redis-cli SET "appointment:stock:$SCHEDULE_ID" "$SAVED_STOCK" > /dev/null

echo ""
echo "=== 测试完成 ==="
