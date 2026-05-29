#!/bin/bash

# 测试创建订单接口并观察 Seata 分布式事务
# 使用方法: ./test-create-order.sh

set -e

echo "=========================================="
echo "测试创建订单 - 观察 Seata 分布式事务"
echo "=========================================="
echo ""

# 等待服务就绪
echo "检查服务状态..."
MAX_RETRIES=30
RETRY_COUNT=0

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    if curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; then
        echo "✓ Gateway 已就绪"
        break
    fi
    RETRY_COUNT=$((RETRY_COUNT + 1))
    echo "等待 Gateway 启动... ($RETRY_COUNT/$MAX_RETRIES)"
    sleep 2
done

if [ $RETRY_COUNT -eq $MAX_RETRIES ]; then
    echo "❌ Gateway 启动超时，请检查 logs/gateway.log"
    exit 1
fi

echo ""
echo "=========================================="
echo "步骤 1: 注册用户"
echo "=========================================="

REGISTER_RESPONSE=$(curl -s -X POST http://localhost:8080/api/user/register \
  -H "Content-Type: application/json" \
  -d '{
    "username":"testuser_'$(date +%s)'",
    "password":"password123",
    "email":"test'$(date +%s)'@example.com"
  }')

echo "$REGISTER_RESPONSE" | jq '.'

if echo "$REGISTER_RESPONSE" | jq -e '.success == true' > /dev/null; then
    echo "✓ 用户注册成功"
else
    echo "⚠ 用户可能已存在，继续登录..."
fi

USERNAME=$(echo "$REGISTER_RESPONSE" | jq -r '.data.username // "testuser_'$(date +%s)'"')

echo ""
echo "=========================================="
echo "步骤 2: 用户登录获取 Token"
echo "=========================================="

LOGIN_RESPONSE=$(curl -s -X POST http://localhost:8080/api/user/login \
  -H "Content-Type: application/json" \
  -d '{
    "username":"'$USERNAME'",
    "password":"password123"
  }')

echo "$LOGIN_RESPONSE" | jq '.'

TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.data.token')

if [ -z "$TOKEN" ] || [ "$TOKEN" == "null" ]; then
    echo "❌ 登录失败，无法获取 Token"
    exit 1
fi

echo "✓ 登录成功"
echo "Token: $TOKEN"

echo ""
echo "=========================================="
echo "步骤 3: 创建订单（触发 Seata 分布式事务）"
echo "=========================================="
echo ""
echo "📝 现在请打开另一个终端运行以下命令查看 Seata 日志："
echo "   tail -f logs/order-service.log | grep -E 'Global|Branch|Seata'"
echo ""
echo "按回车键继续创建订单..."
read

USER_ID=$(echo "$LOGIN_RESPONSE" | jq -r '.data.userId')

CREATE_ORDER_RESPONSE=$(curl -s -X POST http://localhost:8080/api/order/create \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "userId": '$USER_ID',
    "skuId": 1001,
    "quantity": 2,
    "amount": 199.99
  }')

echo "$CREATE_ORDER_RESPONSE" | jq '.'

if echo "$CREATE_ORDER_RESPONSE" | jq -e '.success == true' > /dev/null; then
    echo ""
    echo "✓ 订单创建成功！"
    ORDER_NO=$(echo "$CREATE_ORDER_RESPONSE" | jq -r '.data.orderNo')
    echo "订单号: $ORDER_NO"
else
    echo ""
    echo "❌ 订单创建失败"
fi

echo ""
echo "=========================================="
echo "Seata 分布式事务流程说明"
echo "=========================================="
echo ""
echo "在 logs/order-service.log 中你应该能看到："
echo ""
echo "1. Global transaction begin"
echo "   - Seata 开始全局事务"
echo "   - 生成全局事务 XID"
echo ""
echo "2. Branch transaction register (inventory-service)"
echo "   - 库存服务注册为分支事务"
echo "   - 执行库存扣减"
echo ""
echo "3. Branch transaction register (payment-service)"
echo "   - 支付服务注册为分支事务"
echo "   - 执行支付预处理"
echo ""
echo "4. Branch transaction commit"
echo "   - 所有分支事务提交"
echo ""
echo "5. Global transaction commit"
echo "   - Seata 完成全局事务"
echo ""
echo "=========================================="
echo "查看详细日志："
echo "=========================================="
echo ""
echo "Order Service (Seata 协调者):"
echo "  tail -f logs/order-service.log"
echo ""
echo "Payment Service (分支事务):"
echo "  tail -f logs/payment-service.log"
echo ""
echo "Inventory Service (分支事务):"
echo "  tail -f logs/inventory-service.log"
echo ""
echo "过滤 Seata 相关日志:"
echo "  tail -f logs/order-service.log | grep -E 'Global|Branch|Seata|XID'"
echo ""
