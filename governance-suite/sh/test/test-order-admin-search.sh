#!/usr/bin/env bash

# 在隔离的测试环境运行；显式设置 E2E_WRITE=1 后才创建订单、导出和回填任务。
set -euo pipefail

fail() { echo "验收失败：$*" >&2; exit 1; }
for tool in curl jq python3 rg; do command -v "$tool" >/dev/null || fail "缺少 $tool"; done
[[ "${E2E_WRITE:-}" == "1" ]] || fail "请在隔离测试环境设置 E2E_WRITE=1"
for name in CUSTOMER_TOKEN ORDER_OPERATOR_TOKEN FINANCE_VIEWER_TOKEN INVENTORY_VIEWER_TOKEN TEST_PRODUCT_ID; do
  [[ -n "${!name:-}" ]] || fail "缺少 $name"
done

API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
ADMIN_BASE_URL="${ADMIN_BASE_URL:-${API_BASE_URL}/order-service}"
PERF_BASE_URL="${PERF_BASE_URL:-http://localhost:8081}"
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT
from=$(python3 -c 'from datetime import datetime,timedelta,timezone; print((datetime.now(timezone.utc)-timedelta(hours=1)).isoformat(timespec="seconds").replace("+00:00","Z"))')
to=$(python3 -c 'from datetime import datetime,timedelta,timezone; print((datetime.now(timezone.utc)+timedelta(hours=1)).isoformat(timespec="seconds").replace("+00:00","Z"))')

curl -fsS --max-time 5 "${API_BASE_URL}/actuator/health" >/dev/null || fail "网关未就绪"
curl -fsS --max-time 5 "${PERF_BASE_URL}/actuator/health" >/dev/null || fail "订单服务未就绪"
probe_id="order-admin-e2e-$(python3 -c 'import uuid; print(uuid.uuid4())')"
export_probe=$(curl -sS --max-time 10 -o "$work_dir/probe.json" -w '%{http_code}' \
  "${ADMIN_BASE_URL}/admin/orders/exports/${probe_id}" \
  -H "Authorization: Bearer ${FINANCE_VIEWER_TOKEN}")
[[ "$export_probe" == "404" ]] && jq -e '.code == "EXPORT_NOT_FOUND"' "$work_dir/probe.json" >/dev/null \
  || fail "导出任务表或网关路由未就绪"
rebuild_probe=$(curl -sS --max-time 10 -o "$work_dir/probe.json" -w '%{http_code}' \
  "${ADMIN_BASE_URL}/admin/orders/rebuilds/${probe_id}" \
  -H "Authorization: Bearer ${ORDER_OPERATOR_TOKEN}")
[[ "$rebuild_probe" == "404" ]] && jq -e '.code == "REBUILD_NOT_FOUND"' "$work_dir/probe.json" >/dev/null \
  || fail "回填任务表或网关路由未就绪"

admin_search() {
  local token="$1"; shift
  curl -fsS --max-time 10 -G "${ADMIN_BASE_URL}/admin/orders" \
    -H "Authorization: Bearer ${token}" \
    --data-urlencode "createdFrom=${from}" --data-urlencode "createdTo=${to}" "$@"
}
admin_search "$ORDER_OPERATOR_TOKEN" >/dev/null || fail "检索或审计表未就绪"

wait_order() {
  local token="$1" number="$2" expected_payment="$3" response
  for ((attempt=0; attempt<30; attempt++)); do
    response=$(admin_search "$token" --data-urlencode "orderNo=${number}")
    if jq -e --arg number "$number" --arg payment "$expected_payment" \
      '.success == true and .data.items[0].orderNo == $number and .data.items[0].paymentStatus == $payment' \
      <<<"$response" >/dev/null; then
      echo "$response"
      return
    fi
    sleep 2
  done
  fail "订单 ${number} 的支付投影未在 60 秒内收敛到 ${expected_payment}"
}

echo "创建两笔隔离测试订单，验证 Outbox → RabbitMQ → 读模型投影"
create_order() {
  local response
  response=$(curl -fsS --max-time 15 -X POST "${API_BASE_URL}/api/order/order" \
    -H "Authorization: Bearer ${CUSTOMER_TOKEN}" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: order-admin-e2e-$(python3 -c 'import uuid; print(uuid.uuid4())')" \
    -d "$(jq -nc --arg productId "$TEST_PRODUCT_ID" '{productId:$productId,quantity:1,amountCents:1234}')")
  jq -er '.data.orderNo | select(type == "string" and length > 0)' <<<"$response"
}
first=$(create_order)
second=$(create_order)
[[ "$first" != "$second" ]] || fail "两笔订单号相同"
wait_order "$FINANCE_VIEWER_TOKEN" "$first" "PREPARED" >/dev/null
wait_order "$FINANCE_VIEWER_TOKEN" "$second" "PREPARED" >/dev/null

echo "确认第一笔支付并等待支付读模型投影"
curl -fsS --max-time 15 -X POST "${API_BASE_URL}/api/payment/confirm/${first}" \
  -H "Authorization: Bearer ${CUSTOMER_TOKEN}" | jq -e '.success == true' >/dev/null
wait_order "$FINANCE_VIEWER_TOKEN" "$first" "SUCCEEDED" >"$work_dir/finance.json"

echo "验证角色字段与库存快照"
operator=$(admin_search "$ORDER_OPERATOR_TOKEN" --data-urlencode "orderNo=${first}")
inventory=$(admin_search "$INVENTORY_VIEWER_TOKEN" --data-urlencode "orderNo=${first}")
jq -e '.data.items[0].amountCents == 1234 and .data.items[0].paymentStatus == "SUCCEEDED"' \
  <<<"$operator" >/dev/null
jq -e --arg finance "$(jq -r '.data.items[0].customerId' "$work_dir/finance.json")" \
  '.data.items[0].customerId != $finance' <<<"$operator" >/dev/null
jq -e '.data.items[0] | has("amountCents") | not' <<<"$inventory" >/dev/null
jq -e '.data.items[0].inventorySnapshot.status == "AVAILABLE"' <<<"$inventory" >/dev/null
jq -e '.data.items[0].paymentStatus == "SUCCEEDED" and (.data.items[0].amountCents == 1234)' \
  "$work_dir/finance.json" >/dev/null

echo "验证非法角色与游标分页"
unauthorized=$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' -G "${ADMIN_BASE_URL}/admin/orders" \
  --data-urlencode "createdFrom=${from}" --data-urlencode "createdTo=${to}")
[[ "$unauthorized" == "401" ]] || fail "无令牌检索预期 401，实际 ${unauthorized}"
non_admin=$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' -G "${ADMIN_BASE_URL}/admin/orders" \
  -H "Authorization: Bearer ${CUSTOMER_TOKEN}" \
  --data-urlencode "createdFrom=${from}" --data-urlencode "createdTo=${to}")
[[ "$non_admin" == "403" ]] || fail "普通用户检索预期 403，实际 ${non_admin}"
inventory_sort=$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' -G "${ADMIN_BASE_URL}/admin/orders" \
  -H "Authorization: Bearer ${INVENTORY_VIEWER_TOKEN}" \
  --data-urlencode "createdFrom=${from}" --data-urlencode "createdTo=${to}" \
  --data-urlencode 'sort=AMOUNT_CENTS')
[[ "$inventory_sort" == "400" ]] || fail "库存角色金额排序预期 400，实际 ${inventory_sort}"
page1=$(admin_search "$ORDER_OPERATOR_TOKEN" --data-urlencode "productId=${TEST_PRODUCT_ID}" --data-urlencode 'limit=1')
cursor=$(jq -er '.data.nextCursor | select(type == "string" and length > 0)' <<<"$page1")
page2=$(admin_search "$ORDER_OPERATOR_TOKEN" --data-urlencode "productId=${TEST_PRODUCT_ID}" \
  --data-urlencode 'limit=1' --data-urlencode "cursor=${cursor}")
[[ "$(jq -r '.data.items[0].orderNo' <<<"$page1")" != "$(jq -r '.data.items[0].orderNo' <<<"$page2")" ]] \
  || fail "连续两页重复订单"

echo "验证财务导出冻结、异步完成与下载"
operator_export=$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' -X POST -G \
  "${ADMIN_BASE_URL}/admin/orders/exports" -H "Authorization: Bearer ${ORDER_OPERATOR_TOKEN}" \
  --data-urlencode "createdFrom=${from}" --data-urlencode "createdTo=${to}")
[[ "$operator_export" == "403" ]] || fail "运营角色导出预期 403，实际 ${operator_export}"
export_task=$(curl -fsS --max-time 15 -X POST -G "${ADMIN_BASE_URL}/admin/orders/exports" \
  -H "Authorization: Bearer ${FINANCE_VIEWER_TOKEN}" \
  --data-urlencode "createdFrom=${from}" --data-urlencode "createdTo=${to}" \
  --data-urlencode "orderNo=${first}")
export_id=$(jq -er '.data.id' <<<"$export_task")
for ((attempt=0; attempt<30; attempt++)); do
  export_status=$(curl -fsS --max-time 10 "${ADMIN_BASE_URL}/admin/orders/exports/${export_id}" \
    -H "Authorization: Bearer ${FINANCE_VIEWER_TOKEN}" | jq -r '.data.status')
  [[ "$export_status" == "READY" ]] && break
  [[ "$export_status" != "FAILED" ]] || fail "财务导出失败"
  sleep 2
done
[[ "$export_status" == "READY" ]] || fail "财务导出 60 秒内未完成"
curl -fsS --max-time 15 "${ADMIN_BASE_URL}/admin/orders/exports/${export_id}/download" \
  -H "Authorization: Bearer ${FINANCE_VIEWER_TOKEN}" -o "$work_dir/export.csv"
rg -q "$first" "$work_dir/export.csv" || fail "导出文件不包含目标订单"

echo "验证受控回填与源事实不变"
finance_rebuild=$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' -X POST \
  "${ADMIN_BASE_URL}/admin/orders/rebuilds" -H "Authorization: Bearer ${FINANCE_VIEWER_TOKEN}")
[[ "$finance_rebuild" == "403" ]] || fail "财务角色回填预期 403，实际 ${finance_rebuild}"
before=$(curl -fsS --max-time 10 "${API_BASE_URL}/api/order/order/${first}" \
  -H "Authorization: Bearer ${CUSTOMER_TOKEN}" | jq -c '.data | {orderNo,status,version}')
customer_user_id=$(curl -fsS --max-time 10 "${API_BASE_URL}/api/user/me" \
  -H "Authorization: Bearer ${CUSTOMER_TOKEN}" | jq -er '.data.profile.userId')
payment_before=$(curl -fsS --max-time 10 "${API_BASE_URL}/api/payment/user/${customer_user_id}/payments" \
  -H "Authorization: Bearer ${CUSTOMER_TOKEN}" | jq -ec --arg orderNo "$first" \
  '.data[] | select(.orderNo == $orderNo) | {orderNo,status,version,payNo}')
rebuild_task=$(curl -fsS --max-time 15 -X POST "${ADMIN_BASE_URL}/admin/orders/rebuilds" \
  -H "Authorization: Bearer ${ORDER_OPERATOR_TOKEN}")
rebuild_id=$(jq -er '.data.id' <<<"$rebuild_task")
for ((attempt=0; attempt<60; attempt++)); do
  rebuild_status=$(curl -fsS --max-time 10 "${ADMIN_BASE_URL}/admin/orders/rebuilds/${rebuild_id}" \
    -H "Authorization: Bearer ${ORDER_OPERATOR_TOKEN}" | jq -r '.data.status')
  [[ "$rebuild_status" == "COMPLETED" ]] && break
  [[ "$rebuild_status" != "FAILED" ]] || fail "回填任务失败"
  sleep 2
done
[[ "$rebuild_status" == "COMPLETED" ]] || fail "回填任务 120 秒内未完成"
after=$(curl -fsS --max-time 10 "${API_BASE_URL}/api/order/order/${first}" \
  -H "Authorization: Bearer ${CUSTOMER_TOKEN}" | jq -c '.data | {orderNo,status,version}')
[[ "$before" == "$after" ]] || fail "回填修改了订单事实"
payment_after=$(curl -fsS --max-time 10 "${API_BASE_URL}/api/payment/user/${customer_user_id}/payments" \
  -H "Authorization: Bearer ${CUSTOMER_TOKEN}" | jq -ec --arg orderNo "$first" \
  '.data[] | select(.orderNo == $orderNo) | {orderNo,status,version,payNo}')
[[ "$payment_before" == "$payment_after" ]] || fail "回填修改了支付事实"
wait_order "$FINANCE_VIEWER_TOKEN" "$first" "SUCCEEDED" >/dev/null
rebuild_result=$(curl -fsS --max-time 10 "${ADMIN_BASE_URL}/admin/orders/rebuilds/${rebuild_id}" \
  -H "Authorization: Bearer ${ORDER_OPERATOR_TOKEN}")
skipped=$(jq -er '.data.failureCount' <<<"$rebuild_result")

echo "采集 20 次直连订单服务的检索延迟（秒；仅记录样本，不冒充压力基线）"
for ((sample=0; sample<20; sample++)); do
  curl -fsS --max-time 10 -G "${PERF_BASE_URL}/admin/orders" \
    -H "Authorization: Bearer ${ORDER_OPERATOR_TOKEN}" \
    --data-urlencode "createdFrom=${from}" --data-urlencode "createdTo=${to}" \
    --data-urlencode "orderNo=${first}" -o /dev/null -w '%{time_total}\n' >>"$work_dir/latencies"
done
sort -n "$work_dir/latencies" | awk '{a[NR]=$1} END {printf "样本数=%d p50=%.3fs p95=%.3fs max=%.3fs\n", NR, a[int((NR+1)/2)], a[int((NR*95+99)/100)], a[NR]}'
echo "端到端关键路径通过；订单号：${first}、${second}；导出任务：${export_id}；回填任务：${rebuild_id}；回填失败/跳过计数：${skipped}"
