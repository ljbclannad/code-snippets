#!/bin/bash

# 停止所有微服务的脚本
# 使用方法: ./stop-all-services.sh

echo "=========================================="
echo "停止所有微服务"
echo "=========================================="

if [ -f logs/pids.txt ]; then
    source logs/pids.txt

    echo "停止服务..."

    [ ! -z "$GATEWAY_PID" ] && kill $GATEWAY_PID 2>/dev/null && echo "已停止 Gateway (PID: $GATEWAY_PID)"
    [ ! -z "$USER_PID" ] && kill $USER_PID 2>/dev/null && echo "已停止 User Service (PID: $USER_PID)"
    [ ! -z "$ORDER_PID" ] && kill $ORDER_PID 2>/dev/null && echo "已停止 Order Service (PID: $ORDER_PID)"
    [ ! -z "$PAYMENT_PID" ] && kill $PAYMENT_PID 2>/dev/null && echo "已停止 Payment Service (PID: $PAYMENT_PID)"
    [ ! -z "$INVENTORY_PID" ] && kill $INVENTORY_PID 2>/dev/null && echo "已停止 Inventory Service (PID: $INVENTORY_PID)"
    [ ! -z "$OUTBOX_PID" ] && kill $OUTBOX_PID 2>/dev/null && echo "已停止 Outbox Consumer (PID: $OUTBOX_PID)"

    rm logs/pids.txt
    echo ""
    echo "所有服务已停止！"
else
    echo "未找到 PID 文件，尝试通过端口查找进程..."

    for port in 8080 8081 8082 8083 8084 8090; do
        PID=$(lsof -ti:$port 2>/dev/null)
        if [ ! -z "$PID" ]; then
            kill $PID 2>/dev/null && echo "已停止端口 $port 上的进程 (PID: $PID)"
        fi
    done

    echo ""
    echo "清理完成！"
fi

echo ""
echo "日志文件保留在 logs/ 目录中"
echo ""
