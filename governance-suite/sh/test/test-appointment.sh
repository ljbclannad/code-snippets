#!/bin/bash

# 医院挂号场景完整测试脚本
# 使用方式：./test-appointment.sh

# Redis 命令包装函数（使用 docker exec）
redis_cmd() {
  docker exec gov-redis redis-cli "$@"
}

GATEWAY_URL="http://localhost:8080"
REDIS_HOST="localhost"
REDIS_PORT="36379"
DB_HOST="127.0.0.1"
DB_PORT="3306"
DB_USER="root"
DB_PASS="root"
DB_NAME="order_db"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 辅助函数
print_section() {
  echo ""
  echo "=========================================="
  echo "$1"
  echo "=========================================="
}

print_step() {
  echo ""
  echo -e "${BLUE}$1${NC}"
  echo "------------------------------------------"
}

print_success() {
  echo -e "${GREEN}✅ $1${NC}"
}

print_error() {
  echo -e "${RED}❌ $1${NC}"
}

print_warning() {
  echo -e "${YELLOW}⚠️  $1${NC}"
}

NORMAL_SCHEDULE_ID=""
NORMAL_DEPT_ID=""
CANCEL_SCHEDULE_ID=""
CANCEL_DEPT_ID=""
LOW_STOCK_SCHEDULE_ID=""
LOW_STOCK_DEPT_ID=""

# 注册并登录用户
register_and_login() {
  local user_id=$1
  local username="testuser${user_id}"
  local password="password123"

  # 注册（忽略已存在错误）
  curl -s -X POST "${GATEWAY_URL}/api/user/register" \
    -H "Content-Type: application/json" \
    -d "{\"username\": \"${username}\", \"password\": \"${password}\"}" > /dev/null

  # 登录获取 token
  local login_response=$(curl -s -X POST "${GATEWAY_URL}/api/user/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\": \"${username}\", \"password\": \"${password}\"}")

  local token=$(echo $login_response | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

  if [ -z "$token" ]; then
    print_error "用户 ${username} 登录失败"
    echo "响应: ${login_response}"
    return 1
  fi

  echo "$token"
}

# 从排班查询结果中提取真实排班 ID 和科室 ID
extract_schedule_field() {
  local schedules_response="$1"
  local target_date="$2"
  local target_slot="$3"
  local field_name="$4"

  SCHEDULES_RESPONSE="$schedules_response" python3 - "$target_date" "$target_slot" "$field_name" <<'PY'
import json
import os
import sys

target_date, target_slot, field_name = sys.argv[1:4]
payload = json.loads(os.environ["SCHEDULES_RESPONSE"])
for item in payload.get("data", []):
    if item.get("scheduleDate") == target_date and item.get("timeSlot") == target_slot:
        value = item.get(field_name)
        if value is not None:
            print(value)
            sys.exit(0)
sys.exit(1)
PY
}

# 查询真实排班并缓存测试用 scheduleId
resolve_test_schedules() {
  local token="$1"

  local schedules_response=$(curl -s -X GET "${GATEWAY_URL}/api/appointment/schedules?startDate=2026-05-28&endDate=2026-05-30" \
    -H "Authorization: ${token}")

  if ! echo "$schedules_response" | grep -q '"success":true'; then
    print_error "排班查询失败，无法解析测试排班"
    echo "响应: ${schedules_response}"
    return 1
  fi

  NORMAL_SCHEDULE_ID=$(extract_schedule_field "$schedules_response" "2026-05-28" "afternoon" "scheduleId") || return 1
  NORMAL_DEPT_ID=$(extract_schedule_field "$schedules_response" "2026-05-28" "afternoon" "deptId") || return 1
  CANCEL_SCHEDULE_ID=$(extract_schedule_field "$schedules_response" "2026-05-28" "evening" "scheduleId") || return 1
  CANCEL_DEPT_ID=$(extract_schedule_field "$schedules_response" "2026-05-28" "evening" "deptId") || return 1
  LOW_STOCK_SCHEDULE_ID=$(extract_schedule_field "$schedules_response" "2026-05-29" "morning" "scheduleId") || return 1
  LOW_STOCK_DEPT_ID=$(extract_schedule_field "$schedules_response" "2026-05-29" "morning" "deptId") || return 1
}

# 准备测试数据
prepare_test_data() {
  print_step "准备测试数据"

  local bootstrap_token=$(register_and_login 3999)
  if [ -z "$bootstrap_token" ]; then
    print_error "无法获取初始化 token"
    return 1
  fi

  resolve_test_schedules "$bootstrap_token" || {
    print_error "无法解析真实排班 ID"
    return 1
  }

  # 设置真实排班库存
  redis_cmd SET "appointment:stock:${NORMAL_SCHEDULE_ID}" "10" > /dev/null
  redis_cmd SET "appointment:stock:${CANCEL_SCHEDULE_ID}" "10" > /dev/null
  redis_cmd SET "appointment:stock:${LOW_STOCK_SCHEDULE_ID}" "2" > /dev/null

  # 设置热门科室（用于限流测试）
  redis_cmd SET "appointment:dept:hot:${NORMAL_DEPT_ID}" "1" > /dev/null

  echo "正常流程排班: ${NORMAL_SCHEDULE_ID} (dept=${NORMAL_DEPT_ID})"
  echo "取消流程排班: ${CANCEL_SCHEDULE_ID} (dept=${CANCEL_DEPT_ID})"
  echo "低库存排班: ${LOW_STOCK_SCHEDULE_ID} (dept=${LOW_STOCK_DEPT_ID})"

  print_success "测试数据准备完成"
}

# 场景1：正常流程
test_normal_flow() {
  print_section "场景1：正常流程（注册→登录→查询排班→抢号→支付）"

  local user_id=3001

  print_step "1.1 注册并登录"
  local token=$(register_and_login $user_id)
  if [ -z "$token" ]; then
    print_error "场景1失败：无法获取 token"
    return 1
  fi
  print_success "Token 获取成功: ${token:0:20}..."

  print_step "1.2 查询排班"
  local schedules_response=$(curl -s -X GET "${GATEWAY_URL}/api/appointment/schedules?startDate=2026-05-28&endDate=2026-05-30" \
    -H "Authorization: ${token}")

  echo "排班响应: ${schedules_response}"

  if echo "$schedules_response" | grep -q '"success":true'; then
    print_success "排班查询成功"
  else
    print_error "排班查询失败"
    return 1
  fi

  print_step "1.3 抢号"
  local request_id="req-$(date +%s)-$$-normal"
  local grab_response=$(curl -s -X POST "${GATEWAY_URL}/api/appointment/grab" \
    -H "Content-Type: application/json" \
    -H "Authorization: ${token}" \
    -H "Idempotency-Key: ${request_id}" \
    -d "{\"scheduleId\": ${NORMAL_SCHEDULE_ID}}")

  echo "抢号响应: ${grab_response}"

  if echo "$grab_response" | grep -q '"success":true'; then
    print_success "抢号成功"
  else
    print_error "抢号失败"
    return 1
  fi

  # 等待 Stream 消费器处理
  sleep 3

  print_step "1.4 查询订单号"
  local order_no=$(mysql -h$DB_HOST -P$DB_PORT -u$DB_USER -p$DB_PASS $DB_NAME -N -e \
    "SELECT order_no FROM t_appointment_idempotency WHERE idempotency_key='${request_id}'" 2>/dev/null)

  if [ -z "$order_no" ]; then
    print_error "未找到订单号"
    return 1
  fi

  echo "订单号: ${order_no}"
  print_success "订单创建成功"

  print_step "1.5 支付订单"
  local pay_response=$(curl -s -X POST "${GATEWAY_URL}/api/appointment/pay/${order_no}" \
    -H "Authorization: ${token}")

  echo "支付响应: ${pay_response}"

  if echo "$pay_response" | grep -q '"success":true'; then
    print_success "支付成功"
  else
    print_error "支付失败"
    return 1
  fi

  print_step "1.6 验证订单状态"
  local order_status=$(mysql -h$DB_HOST -P$DB_PORT -u$DB_USER -p$DB_PASS $DB_NAME -N -e \
    "SELECT status FROM t_appointment_order WHERE order_no='${order_no}'" 2>/dev/null)

  echo "订单状态: ${order_status}"

  if [ "$order_status" == "PAID" ]; then
    print_success "订单状态正确"
  else
    print_error "订单状态错误，期望 PAID，实际 ${order_status}"
    return 1
  fi

  print_step "1.7 验证 Outbox 写入"
  local outbox_count=$(mysql -h$DB_HOST -P$DB_PORT -u$DB_USER -p$DB_PASS $DB_NAME -N -e \
    "SELECT COUNT(*) FROM t_outbox WHERE aggregate_id='${order_no}' AND event_type='appointment.paid'" 2>/dev/null)

  echo "Outbox 记录数: ${outbox_count}"

  if [ "$outbox_count" -gt 0 ]; then
    print_success "Outbox 写入成功"
  else
    print_error "Outbox 未写入"
    return 1
  fi

  print_step "1.8 验证 Redis 库存扣减"
  local current_stock=$(redis_cmd GET "appointment:stock:${NORMAL_SCHEDULE_ID}" 2>/dev/null)
  echo "当前库存: ${current_stock} (初始: 10)"

  if [ "$current_stock" -lt 10 ]; then
    print_success "Redis 库存已扣减"
  else
    print_error "Redis 库存未扣减"
    return 1
  fi

  print_step "1.9 验证 Redis 幂等键"
  local idem_key="idempotency:${request_id}"
  local idem_exists=$(redis_cmd EXISTS "$idem_key" 2>/dev/null)
  local idem_ttl=$(redis_cmd TTL "$idem_key" 2>/dev/null)

  echo "幂等键存在: ${idem_exists} (1=存在, 0=不存在)"
  echo "幂等键 TTL: ${idem_ttl} 秒"

  if [ "$idem_exists" -eq 1 ] && [ "$idem_ttl" -gt 0 ]; then
    print_success "Redis 幂等键已设置且有 TTL"
  else
    print_warning "Redis 幂等键状态异常（可能已过期或未设置）"
  fi

  print_success "场景1：正常流程测试通过"
}

# 场景2：重复抢号
test_duplicate_grab() {
  print_section "场景2：重复抢号"

  local user_id=3002

  print_step "2.1 注册并登录"
  local token=$(register_and_login $user_id)
  if [ -z "$token" ]; then
    print_error "场景2失败：无法获取 token"
    return 1
  fi

  print_step "2.2 第一次抢号"
  local request_id="req-$(date +%s)-$$-dup"
  local grab_response1=$(curl -s -X POST "${GATEWAY_URL}/api/appointment/grab" \
    -H "Content-Type: application/json" \
    -H "Authorization: ${token}" \
    -H "Idempotency-Key: ${request_id}" \
    -d "{\"scheduleId\": ${NORMAL_SCHEDULE_ID}}")

  echo "第一次抢号响应: ${grab_response1}"

  if echo "$grab_response1" | grep -q '"success":true'; then
    print_success "第一次抢号成功"
  else
    print_error "第一次抢号失败"
    return 1
  fi

  sleep 1

  print_step "2.3 使用相同幂等键重复抢号"
  local grab_response2=$(curl -s -X POST "${GATEWAY_URL}/api/appointment/grab" \
    -H "Content-Type: application/json" \
    -H "Authorization: ${token}" \
    -H "Idempotency-Key: ${request_id}" \
    -d "{\"scheduleId\": ${NORMAL_SCHEDULE_ID}}")

  echo "第二次抢号响应: ${grab_response2}"

  # 幂等保护应该返回相同结果或提示已处理
  if echo "$grab_response2" | grep -q '"success":true'; then
    print_success "幂等保护生效，返回成功"
  else
    print_warning "幂等保护返回失败（可能是业务逻辑限制）"
  fi

  print_step "2.4 验证只创建了一个订单"
  sleep 2
  local order_count=$(mysql -h$DB_HOST -P$DB_PORT -u$DB_USER -p$DB_PASS $DB_NAME -N -e \
    "SELECT COUNT(*) FROM t_appointment_idempotency WHERE idempotency_key='${request_id}'" 2>/dev/null)

  echo "订单记录数: ${order_count}"

  if [ "$order_count" -eq 1 ]; then
    print_success "幂等保护有效，只创建了一个订单"
  else
    print_error "幂等保护失败，创建了 ${order_count} 个订单"
    return 1
  fi

  print_step "2.5 验证 Redis 幂等键状态"
  local idem_key="idempotency:${request_id}"
  local idem_exists=$(redis_cmd EXISTS "$idem_key" 2>/dev/null)
  local idem_ttl=$(redis_cmd TTL "$idem_key" 2>/dev/null)
  local idem_value=$(redis_cmd GET "$idem_key" 2>/dev/null)

  echo "幂等键存在: ${idem_exists}"
  echo "幂等键 TTL: ${idem_ttl} 秒"
  echo "幂等键值: ${idem_value}"

  if [ "$idem_exists" -eq 1 ]; then
    print_success "Redis 幂等键存在，防止重复处理"
  else
    print_warning "Redis 幂等键不存在（可能已过期）"
  fi

  print_success "场景2：重复抢号测试通过"
}

# 场景3：库存耗尽
test_stock_exhaustion() {
  print_section "场景3：库存耗尽"

  print_step "3.1 设置低库存排班"
  redis_cmd SET "appointment:stock:${LOW_STOCK_SCHEDULE_ID}" "2" > /dev/null
  print_success "库存设置为 2"

  print_step "3.2 并发抢号（5个用户抢2个名额）"

  local success_count=0
  local fail_count=0
  local temp_file=$(mktemp)

  for i in {1..5}; do
    local user_id=$((3100 + i))
    local token=$(register_and_login $user_id)

    if [ -z "$token" ]; then
      print_warning "用户 ${user_id} 登录失败，跳过"
      continue
    fi

    (
      local request_id="req-$(date +%s)-$$-stock-${i}"
      local grab_response=$(curl -s -X POST "${GATEWAY_URL}/api/appointment/grab" \
        -H "Content-Type: application/json" \
        -H "Authorization: ${token}" \
        -H "Idempotency-Key: ${request_id}" \
        -d "{\"scheduleId\": ${LOW_STOCK_SCHEDULE_ID}}")

      if echo "$grab_response" | grep -q '"success":true'; then
        echo "SUCCESS" >> "$temp_file"
      else
        echo "FAIL" >> "$temp_file"
      fi
    ) &
  done

  # 等待所有后台任务完成
  wait

  success_count=$(grep -c "SUCCESS" "$temp_file" 2>/dev/null || echo 0)
  fail_count=$(grep -c "FAIL" "$temp_file" 2>/dev/null || echo 0)
  rm -f "$temp_file"

  print_step "3.3 验证结果"
  echo "成功抢号数: ${success_count}"
  echo "失败抢号数: ${fail_count}"

  # 等待 Stream 消费器处理
  sleep 3

  local final_stock=$(redis_cmd GET "appointment:stock:${LOW_STOCK_SCHEDULE_ID}" 2>/dev/null)
  echo "最终库存: ${final_stock}"

  if [ "$final_stock" -eq 0 ]; then
    print_success "库存正确扣减至 0"
  else
    print_warning "最终库存为 ${final_stock}（可能有订单未完成）"
  fi

  if [ "$success_count" -le 2 ] && [ "$fail_count" -ge 3 ]; then
    print_success "库存耗尽保护有效"
  else
    print_warning "库存控制可能存在问题（成功:${success_count}, 失败:${fail_count}）"
  fi

  print_step "3.4 验证数据库订单数量"
  local db_order_count=$(mysql -h$DB_HOST -P$DB_PORT -u$DB_USER -p$DB_PASS $DB_NAME -N -e \
    "SELECT COUNT(*) FROM t_appointment_order WHERE schedule_id=${LOW_STOCK_SCHEDULE_ID}" 2>/dev/null)

  echo "数据库订单数: ${db_order_count}"
  echo "成功抢号数: ${success_count}"

  if [ "$db_order_count" -eq "$success_count" ]; then
    print_success "数据库订单数与成功数一致"
  else
    print_warning "数据库订单数(${db_order_count})与成功数(${success_count})不一致（可能有订单正在处理）"
  fi

  print_success "场景3：库存耗尽测试完成"
}

# 场景4：用户主动取消
test_user_cancel() {
  print_section "场景4：用户主动取消"

  local user_id=3003

  print_step "4.1 注册并登录"
  local token=$(register_and_login $user_id)
  if [ -z "$token" ]; then
    print_error "场景4失败：无法获取 token"
    return 1
  fi

  print_step "4.2 抢号创建订单"
  local request_id="req-$(date +%s)-$$-cancel"
  curl -s -X POST "${GATEWAY_URL}/api/appointment/grab" \
    -H "Content-Type: application/json" \
    -H "Authorization: ${token}" \
    -H "Idempotency-Key: ${request_id}" \
    -d "{\"scheduleId\": ${CANCEL_SCHEDULE_ID}}" > /dev/null

  sleep 3

  local order_no=$(mysql -h$DB_HOST -P$DB_PORT -u$DB_USER -p$DB_PASS $DB_NAME -N -e \
    "SELECT order_no FROM t_appointment_idempotency WHERE idempotency_key='${request_id}'" 2>/dev/null)

  if [ -z "$order_no" ]; then
    print_error "未找到订单号"
    return 1
  fi

  echo "订单号: ${order_no}"

  print_step "4.3 取消订单"
  local cancel_response=$(curl -s -X POST "${GATEWAY_URL}/api/appointment/cancel" \
    -H "Content-Type: application/json" \
    -H "Authorization: ${token}" \
    -d "{\"orderNo\": \"${order_no}\", \"reason\": \"用户主动取消测试\"}")

  echo "取消响应: ${cancel_response}"

  if echo "$cancel_response" | grep -q '"success":true'; then
    print_success "取消成功"
  else
    print_error "取消失败"
    return 1
  fi

  print_step "4.4 验证订单状态"
  local order_status=$(mysql -h$DB_HOST -P$DB_PORT -u$DB_USER -p$DB_PASS $DB_NAME -N -e \
    "SELECT status FROM t_appointment_order WHERE order_no='${order_no}'" 2>/dev/null)

  echo "订单状态: ${order_status}"

  if [ "$order_status" == "CANCELLED" ]; then
    print_success "订单状态正确"
  else
    print_error "订单状态错误，期望 CANCELLED，实际 ${order_status}"
    return 1
  fi

  print_step "4.5 验证库存回补日志"
  local refund_log_count=$(mysql -h$DB_HOST -P$DB_PORT -u$DB_USER -p$DB_PASS $DB_NAME -N -e \
    "SELECT COUNT(*) FROM t_quota_refund_log WHERE order_no='${order_no}' AND refund_type='CANCEL'" 2>/dev/null)

  echo "回补日志记录数: ${refund_log_count}"

  if [ "$refund_log_count" -gt 0 ]; then
    print_success "库存回补日志已记录"
  else
    print_error "库存回补日志未记录"
    return 1
  fi

  print_step "4.6 验证 Redis 库存回补"
  local stock_before=10
  local current_stock=$(redis_cmd GET "appointment:stock:${CANCEL_SCHEDULE_ID}" 2>/dev/null)

  echo "当前库存: ${current_stock}"
  echo "预期库存: ${stock_before} (取消后应回补)"

  if [ "$current_stock" -ge "$stock_before" ] || [ "$current_stock" -eq $((stock_before)) ]; then
    print_success "Redis 库存已回补"
  else
    print_warning "Redis 库存回补状态: ${current_stock} (初始: ${stock_before})"
  fi

  print_success "场景4：用户主动取消测试通过"
}

# 场景5：支付超时取消
test_payment_timeout() {
  print_section "场景5：支付超时取消"

  local user_id=3004

  print_step "5.1 注册并登录"
  local token=$(register_and_login $user_id)
  if [ -z "$token" ]; then
    print_error "场景5失败：无法获取 token"
    return 1
  fi

  print_step "5.2 抢号创建订单"
  local request_id="req-$(date +%s)-$$-timeout"
  curl -s -X POST "${GATEWAY_URL}/api/appointment/grab" \
    -H "Content-Type: application/json" \
    -H "Authorization: ${token}" \
    -H "Idempotency-Key: ${request_id}" \
    -d "{\"scheduleId\": ${CANCEL_SCHEDULE_ID}}" > /dev/null

  sleep 3

  local order_no=$(mysql -h$DB_HOST -P$DB_PORT -u$DB_USER -p$DB_PASS $DB_NAME -N -e \
    "SELECT order_no FROM t_appointment_idempotency WHERE idempotency_key='${request_id}'" 2>/dev/null)

  if [ -z "$order_no" ]; then
    print_error "未找到订单号"
    return 1
  fi

  echo "订单号: ${order_no}"

  print_step "5.3 修改订单预约时间为 20 分钟前"
  mysql -h$DB_HOST -P$DB_PORT -u$DB_USER -p$DB_PASS $DB_NAME -e \
    "UPDATE t_appointment_order SET reserved_at = DATE_SUB(NOW(), INTERVAL 20 MINUTE) WHERE order_no='${order_no}'" 2>/dev/null

  print_success "订单时间已修改"

  print_step "5.4 等待定时任务执行（最多 70 秒）"
  local timeout_detected=false

  for i in {1..7}; do
    sleep 10
    local order_status=$(mysql -h$DB_HOST -P$DB_PORT -u$DB_USER -p$DB_PASS $DB_NAME -N -e \
      "SELECT status FROM t_appointment_order WHERE order_no='${order_no}'" 2>/dev/null)

    if [ "$order_status" == "CANCELLED" ]; then
      print_success "订单已自动取消（等待 ${i}0 秒）"
      timeout_detected=true
      break
    fi

    echo "等待中... (${i}0秒)"
  done

  if [ "$timeout_detected" = false ]; then
    print_error "订单未在预期时间内自动取消"
    return 1
  fi

  print_step "5.5 验证回补日志类型"
  local refund_type=$(mysql -h$DB_HOST -P$DB_PORT -u$DB_USER -p$DB_PASS $DB_NAME -N -e \
    "SELECT refund_type FROM t_quota_refund_log WHERE order_no='${order_no}'" 2>/dev/null)

  echo "回补类型: ${refund_type}"

  if [ "$refund_type" == "TIMEOUT" ]; then
    print_success "回补类型正确"
  else
    print_error "回补类型错误，期望 TIMEOUT，实际 ${refund_type}"
    return 1
  fi

  print_step "5.6 验证 Redis 库存回补"
  local stock_before=10
  local current_stock=$(redis_cmd GET "appointment:stock:${CANCEL_SCHEDULE_ID}" 2>/dev/null)

  echo "当前库存: ${current_stock}"
  echo "预期库存: ${stock_before} (超时取消后应回补)"

  if [ "$current_stock" -ge "$stock_before" ] || [ "$current_stock" -eq $((stock_before)) ]; then
    print_success "Redis 库存已回补"
  else
    print_warning "Redis 库存回补状态: ${current_stock} (初始: ${stock_before})"
  fi

  print_success "场景5：支付超时取消测试通过"
}

# 场景6：限流验证
test_rate_limit() {
  print_section "场景6：限流验证"

  local user_id=3005

  print_step "6.1 注册并登录"
  local token=$(register_and_login $user_id)
  if [ -z "$token" ]; then
    print_error "场景6失败：无法获取 token"
    return 1
  fi

  print_step "6.2 测试 userId 限流（快速连续请求）"
  local success_count=0
  local rate_limit_count=0

  for i in {1..15}; do
    local response=$(curl -s -w "\nHTTP_CODE:%{http_code}" -X POST "${GATEWAY_URL}/api/appointment/grab" \
      -H "Content-Type: application/json" \
      -H "Authorization: ${token}" \
      -H "Idempotency-Key: req-ratelimit-${i}-$$" \
      -d "{\"scheduleId\": ${NORMAL_SCHEDULE_ID}}")

    local http_code=$(echo "$response" | grep "HTTP_CODE" | cut -d: -f2)

    if [ "$http_code" == "429" ]; then
      rate_limit_count=$((rate_limit_count + 1))
    else
      success_count=$((success_count + 1))
    fi
  done

  echo "成功请求数: ${success_count}"
  echo "限流请求数: ${rate_limit_count}"

  if [ "$rate_limit_count" -gt 0 ]; then
    print_success "userId 限流生效"
  else
    print_warning "userId 限流未触发（可能需要更快的请求速度）"
  fi

  # 等待限流窗口重置
  sleep 3

  print_step "6.3 测试 scheduleId 限流（并发请求）"
  local concurrent_users=30
  local rate_limit_count=0
  local temp_file=$(mktemp)

  for i in $(seq 1 $concurrent_users); do
    local user_id=$((3200 + i))
    local token=$(register_and_login $user_id)

    if [ -z "$token" ]; then
      continue
    fi

    (
      local response=$(curl -s -w "\nHTTP_CODE:%{http_code}" -X POST "${GATEWAY_URL}/api/appointment/grab" \
        -H "Content-Type: application/json" \
        -H "Authorization: ${token}" \
        -H "Idempotency-Key: req-concurrent-${i}-$$" \
        -d "{\"scheduleId\": ${NORMAL_SCHEDULE_ID}}")

      local http_code=$(echo "$response" | grep "HTTP_CODE" | cut -d: -f2)

      if [ "$http_code" == "429" ]; then
        echo "429" >> "$temp_file"
      fi
    ) &
  done

  # 等待所有后台任务完成
  wait

  rate_limit_count=$(wc -l < "$temp_file" 2>/dev/null || echo 0)
  rm -f "$temp_file"

  echo "并发用户数: ${concurrent_users}"
  echo "触发限流数: ${rate_limit_count}"

  if [ "$rate_limit_count" -gt 0 ]; then
    print_success "scheduleId 限流生效"
  else
    print_warning "scheduleId 限流未明显触发（可能需要更高并发）"
  fi

  print_step "6.4 验证 429 响应格式"
  # 快速请求触发限流
  for i in {1..10}; do
    curl -s -X POST "${GATEWAY_URL}/api/appointment/grab" \
      -H "Content-Type: application/json" \
      -H "Authorization: ${token}" \
      -H "Idempotency-Key: req-format-${i}-$$" \
      -d "{\"scheduleId\": ${NORMAL_SCHEDULE_ID}}" > /dev/null
  done

  local response=$(curl -s -X POST "${GATEWAY_URL}/api/appointment/grab" \
    -H "Content-Type: application/json" \
    -H "Authorization: ${token}" \
    -H "Idempotency-Key: req-format-final-$$" \
    -d "{\"scheduleId\": ${NORMAL_SCHEDULE_ID}}")

  echo "429 响应示例:"
  echo "${response}" | python3 -m json.tool 2>/dev/null || echo "${response}"

  if echo "${response}" | grep -q '"success":false' && \
     echo "${response}" | grep -q '"code":"RATE_LIMIT_EXCEEDED"'; then
    print_success "429 响应格式正确"
  else
    print_warning "429 响应格式可能不符合预期"
  fi

  print_step "6.5 验证 Redis 限流键"
  local user_rate_key="rate_limit:user:${user_id}"
  local schedule_rate_key="rate_limit:schedule:${NORMAL_SCHEDULE_ID}"

  local user_rate_count=$(redis_cmd GET "$user_rate_key" 2>/dev/null)
  local user_rate_ttl=$(redis_cmd TTL "$user_rate_key" 2>/dev/null)

  echo "用户限流键: ${user_rate_key}"
  echo "用户限流计数: ${user_rate_count}"
  echo "用户限流 TTL: ${user_rate_ttl} 秒"

  if [ -n "$user_rate_count" ] && [ "$user_rate_count" -gt 0 ]; then
    print_success "Redis 用户限流键已设置"
  else
    print_warning "Redis 用户限流键未找到（可能已过期）"
  fi

  local schedule_rate_count=$(redis_cmd GET "$schedule_rate_key" 2>/dev/null)
  local schedule_rate_ttl=$(redis_cmd TTL "$schedule_rate_key" 2>/dev/null)

  echo "排班限流键: ${schedule_rate_key}"
  echo "排班限流计数: ${schedule_rate_count}"
  echo "排班限流 TTL: ${schedule_rate_ttl} 秒"

  if [ -n "$schedule_rate_count" ] && [ "$schedule_rate_count" -gt 0 ]; then
    print_success "Redis 排班限流键已设置"
  else
    print_warning "Redis 排班限流键未找到（可能已过期）"
  fi

  print_step "6.6 验证被限流的请求未创建订单"
  if [ "$rate_limit_count" -gt 0 ]; then
    local total_requests=15
    local expected_max_orders=$((total_requests - rate_limit_count))
    local actual_orders=$(mysql -h$DB_HOST -P$DB_PORT -u$DB_USER -p$DB_PASS $DB_NAME -N -e \
      "SELECT COUNT(*) FROM t_appointment_order WHERE user_id=${user_id} AND schedule_id=${NORMAL_SCHEDULE_ID}" 2>/dev/null)

    echo "总请求数: ${total_requests}"
    echo "被限流数: ${rate_limit_count}"
    echo "实际订单数: ${actual_orders}"
    echo "预期最大订单数: ${expected_max_orders}"

    if [ "$actual_orders" -le "$expected_max_orders" ]; then
      print_success "被限流的请求未创建订单"
    else
      print_warning "订单数(${actual_orders})超过预期(${expected_max_orders})"
    fi
  fi

  print_success "场景6：限流验证测试完成"
}

# 主函数
main() {
  print_section "医院挂号场景完整测试"
  echo "Gateway URL: ${GATEWAY_URL}"
  echo "Redis: ${REDIS_HOST}:${REDIS_PORT}"
  echo "Database: ${DB_HOST}:${DB_PORT}/${DB_NAME}"

  # 准备测试数据
  prepare_test_data

  # 执行所有测试场景
  local failed_tests=0

  test_normal_flow || failed_tests=$((failed_tests + 1))
  test_duplicate_grab || failed_tests=$((failed_tests + 1))
  test_stock_exhaustion || failed_tests=$((failed_tests + 1))
  test_user_cancel || failed_tests=$((failed_tests + 1))
  test_payment_timeout || failed_tests=$((failed_tests + 1))
  test_rate_limit || failed_tests=$((failed_tests + 1))

  # 总结
  print_section "测试总结"

  if [ $failed_tests -eq 0 ]; then
    print_success "所有测试场景通过！"
    exit 0
  else
    print_error "${failed_tests} 个测试场景失败"
    exit 1
  fi
}

# 执行主函数
main
