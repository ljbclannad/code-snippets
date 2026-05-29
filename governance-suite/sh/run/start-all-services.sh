#!/bin/bash

# 启动所有微服务的脚本
# 使用方法: ./start-all-services.sh

set -e

echo "=========================================="
echo "启动所有微服务"
echo "=========================================="

# 检查基础设施是否运行
echo "检查基础设施状态..."
if ! docker compose ps | grep -q "Up"; then
    echo "基础设施未运行，正在启动..."
    docker compose up -d
    echo "等待基础设施就绪（30秒）..."
    sleep 30
fi

# 创建日志目录
mkdir -p logs

echo ""
echo "正在启动服务（日志输出到 logs/ 目录）..."
echo ""

# 启动网关
echo "启动 Gateway (端口 8080)..."
nohup mvn -q -pl gateway spring-boot:run > logs/gateway.log 2>&1 &
GATEWAY_PID=$!
echo "Gateway PID: $GATEWAY_PID"

# 启动用户服务
echo "启动 User Service (端口 8084)..."
nohup mvn -q -pl user-service spring-boot:run > logs/user-service.log 2>&1 &
USER_PID=$!
echo "User Service PID: $USER_PID"

# 启动订单服务
echo "启动 Order Service (端口 8081) - 重点观察 Seata 日志..."
nohup mvn -q -pl order-service spring-boot:run > logs/order-service.log 2>&1 &
ORDER_PID=$!
echo "Order Service PID: $ORDER_PID"

# 启动支付服务
echo "启动 Payment Service (端口 8082)..."
nohup mvn -q -pl payment-service spring-boot:run > logs/payment-service.log 2>&1 &
PAYMENT_PID=$!
echo "Payment Service PID: $PAYMENT_PID"

# 启动库存服务
echo "启动 Inventory Service (端口 8083)..."
nohup mvn -q -pl inventory-service spring-boot:run > logs/inventory-service.log 2>&1 &
INVENTORY_PID=$!
echo "Inventory Service PID: $INVENTORY_PID"

# 启动 Outbox 消费者
echo "启动 Outbox Consumer (端口 8090)..."
nohup mvn -q -pl outbox-consumer spring-boot:run > logs/outbox-consumer.log 2>&1 &
OUTBOX_PID=$!
echo "Outbox Consumer PID: $OUTBOX_PID"

# 保存 PID 到文件
cat > logs/pids.txt <<EOF
GATEWAY_PID=$GATEWAY_PID
USER_PID=$USER_PID
ORDER_PID=$ORDER_PID
PAYMENT_PID=$PAYMENT_PID
INVENTORY_PID=$INVENTORY_PID
OUTBOX_PID=$OUTBOX_PID
EOF

echo ""
echo "=========================================="
echo "所有服务已启动！"
echo "=========================================="
echo ""
echo "PID 信息已保存到 logs/pids.txt"
echo "日志文件位置："
echo "  - logs/gateway.log"
echo "  - logs/user-service.log"
echo "  - logs/order-service.log (Seata 日志)"
echo "  - logs/payment-service.log"
echo "  - logs/inventory-service.log"
echo "  - logs/outbox-consumer.log"
echo ""
echo "等待服务启动完成（约 60 秒）..."
echo "你可以运行以下命令实时查看 Seata 日志："
echo "  tail -f logs/order-service.log"
echo ""
echo "服务就绪后，运行测试脚本："
echo "  ./test-create-order.sh"
echo ""
echo "停止所有服务："
echo "  ./stop-all-services.sh"
echo ""
