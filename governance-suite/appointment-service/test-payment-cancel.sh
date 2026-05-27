#!/bin/bash

# 测试支付与取消流程
# 使用方式：./test-payment-cancel.sh

BASE_URL="http://localhost:8089"
USER_ID=1001
SCHEDULE_ID=1

echo "=========================================="
echo "Part 6: 支付与取消流程测试"
echo "=========================================="

# 生成唯一的请求ID
REQUEST_ID="req-$(date +%s)-$$"

echo ""
echo "1. 测试抢号（创建订单）"
echo "------------------------------------------"
GRAB_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/appointment/grab" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: ${USER_ID}" \
  -H "Idempotency-Key: ${REQUEST_ID}" \
  -d "{\"scheduleId\": ${SCHEDULE_ID}}")

echo "抢号响应: ${GRAB_RESPONSE}"

# 等待 Stream 消费器处理
echo "等待订单创建..."
sleep 2

# 从幂等表查询订单号
ORDER_NO=$(mysql -h127.0.0.1 -P3306 -uroot -proot order_db -N -e \
  "SELECT order_no FROM t_appointment_idempotency WHERE idempotency_key='${REQUEST_ID}'" 2>/dev/null)

if [ -z "$ORDER_NO" ]; then
  echo "错误：未找到订单号"
  exit 1
fi

echo "订单号: ${ORDER_NO}"

echo ""
echo "2. 测试查询订单"
echo "------------------------------------------"
ORDER_RESPONSE=$(curl -s -X GET "${BASE_URL}/api/appointment/order/${ORDER_NO}" \
  -H "X-User-Id: ${USER_ID}")

echo "订单详情: ${ORDER_RESPONSE}"

echo ""
echo "3. 测试正常支付流程"
echo "------------------------------------------"
PAY_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/appointment/pay/${ORDER_NO}" \
  -H "X-User-Id: ${USER_ID}")

echo "支付响应: ${PAY_RESPONSE}"

# 验证订单状态
ORDER_STATUS=$(mysql -h127.0.0.1 -P3306 -uroot -proot order_db -N -e \
  "SELECT status FROM t_appointment_order WHERE order_no='${ORDER_NO}'" 2>/dev/null)

echo "订单状态: ${ORDER_STATUS}"

if [ "$ORDER_STATUS" != "PAID" ]; then
  echo "错误：订单状态不是 PAID"
  exit 1
fi

# 验证 Outbox 写入
OUTBOX_COUNT=$(mysql -h127.0.0.1 -P3306 -uroot -proot order_db -N -e \
  "SELECT COUNT(*) FROM t_outbox WHERE aggregate_id='${ORDER_NO}' AND event_type='appointment.paid'" 2>/dev/null)

echo "Outbox 记录数: ${OUTBOX_COUNT}"

if [ "$OUTBOX_COUNT" -eq 0 ]; then
  echo "错误：Outbox 未写入"
  exit 1
fi

echo ""
echo "4. 测试用户主动取消（创建新订单）"
echo "------------------------------------------"

# 创建新订单用于取消测试
REQUEST_ID_2="req-$(date +%s)-$$-2"

curl -s -X POST "${BASE_URL}/api/appointment/grab" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: ${USER_ID}" \
  -H "Idempotency-Key: ${REQUEST_ID_2}" \
  -d "{\"scheduleId\": ${SCHEDULE_ID}}" > /dev/null

sleep 2

ORDER_NO_2=$(mysql -h127.0.0.1 -P3306 -uroot -proot order_db -N -e \
  "SELECT order_no FROM t_appointment_idempotency WHERE idempotency_key='${REQUEST_ID_2}'" 2>/dev/null)

echo "新订单号: ${ORDER_NO_2}"

# 取消订单
CANCEL_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/appointment/cancel" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: ${USER_ID}" \
  -d "{\"orderNo\": \"${ORDER_NO_2}\", \"reason\": \"测试取消\"}")

echo "取消响应: ${CANCEL_RESPONSE}"

# 验证订单状态
ORDER_STATUS_2=$(mysql -h127.0.0.1 -P3306 -uroot -proot order_db -N -e \
  "SELECT status FROM t_appointment_order WHERE order_no='${ORDER_NO_2}'" 2>/dev/null)

echo "订单状态: ${ORDER_STATUS_2}"

if [ "$ORDER_STATUS_2" != "CANCELLED" ]; then
  echo "错误：订单状态不是 CANCELLED"
  exit 1
fi

# 验证库存回补日志
REFUND_LOG_COUNT=$(mysql -h127.0.0.1 -P3306 -uroot -proot order_db -N -e \
  "SELECT COUNT(*) FROM t_quota_refund_log WHERE order_no='${ORDER_NO_2}' AND refund_type='CANCEL'" 2>/dev/null)

echo "回补日志记录数: ${REFUND_LOG_COUNT}"

if [ "$REFUND_LOG_COUNT" -eq 0 ]; then
  echo "错误：回补日志未写入"
  exit 1
fi

echo ""
echo "5. 测试超时自动取消"
echo "------------------------------------------"

# 创建超时订单（手动修改 reserved_at 为 20 分钟前）
REQUEST_ID_3="req-$(date +%s)-$$-3"

curl -s -X POST "${BASE_URL}/api/appointment/grab" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: ${USER_ID}" \
  -H "Idempotency-Key: ${REQUEST_ID_3}" \
  -d "{\"scheduleId\": ${SCHEDULE_ID}}" > /dev/null

sleep 2

ORDER_NO_3=$(mysql -h127.0.0.1 -P3306 -uroot -proot order_db -N -e \
  "SELECT order_no FROM t_appointment_idempotency WHERE idempotency_key='${REQUEST_ID_3}'" 2>/dev/null)

echo "超时测试订单号: ${ORDER_NO_3}"

# 修改 reserved_at 为 20 分钟前
mysql -h127.0.0.1 -P3306 -uroot -proot order_db -e \
  "UPDATE t_appointment_order SET reserved_at = DATE_SUB(NOW(), INTERVAL 20 MINUTE) WHERE order_no='${ORDER_NO_3}'" 2>/dev/null

echo "已将订单预约时间修改为 20 分钟前"

# 等待定时任务执行（最多等待 70 秒）
echo "等待定时任务执行（最多 70 秒）..."
for i in {1..7}; do
  sleep 10
  ORDER_STATUS_3=$(mysql -h127.0.0.1 -P3306 -uroot -proot order_db -N -e \
    "SELECT status FROM t_appointment_order WHERE order_no='${ORDER_NO_3}'" 2>/dev/null)

  if [ "$ORDER_STATUS_3" == "CANCELLED" ]; then
    echo "订单已自动取消"
    break
  fi

  echo "等待中... (${i}0秒)"
done

# 验证订单状态
ORDER_STATUS_3=$(mysql -h127.0.0.1 -P3306 -uroot -proot order_db -N -e \
  "SELECT status FROM t_appointment_order WHERE order_no='${ORDER_NO_3}'" 2>/dev/null)

echo "订单状态: ${ORDER_STATUS_3}"

if [ "$ORDER_STATUS_3" != "CANCELLED" ]; then
  echo "错误：订单未自动取消"
  exit 1
fi

# 验证回补日志类型
REFUND_TYPE=$(mysql -h127.0.0.1 -P3306 -uroot -proot order_db -N -e \
  "SELECT refund_type FROM t_quota_refund_log WHERE order_no='${ORDER_NO_3}'" 2>/dev/null)

echo "回补类型: ${REFUND_TYPE}"

if [ "$REFUND_TYPE" != "TIMEOUT" ]; then
  echo "错误：回补类型不是 TIMEOUT"
  exit 1
fi

echo ""
echo "=========================================="
echo "所有测试通过！✅"
echo "=========================================="
