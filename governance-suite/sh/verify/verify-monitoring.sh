#!/bin/bash

echo "=========================================="
echo "  微服务 Prometheus 监控验证"
echo "=========================================="
echo ""

echo "1. 检查 Prometheus 端点配置..."
echo "   服务名称                    端口    状态"
echo "   --------------------------------------------------------"

check_service() {
    local service=$1
    local port=$2

    if curl -s "http://localhost:$port/actuator/health" > /dev/null 2>&1; then
        if curl -s "http://localhost:$port/actuator/prometheus" > /dev/null 2>&1; then
            metric_count=$(curl -s "http://localhost:$port/actuator/prometheus" | grep -c '^[a-z]')
            printf "   %-30s %-7s ✓ 运行中 (%d 个指标)\n" "$service" "$port" "$metric_count"
        else
            printf "   %-30s %-7s ✗ 端点未暴露\n" "$service" "$port"
        fi
    else
        printf "   %-30s %-7s - 未启动\n" "$service" "$port"
    fi
}

check_service "gateway" "8080"
check_service "order-service" "8081"
check_service "payment-service" "8082"
check_service "inventory-service" "8083"
check_service "user-service" "8084"
check_service "outbox-consumer" "8090"
check_service "ai-chat-service" "8100"

echo ""
echo "2. 检查 Prometheus 监控目标..."
if curl -s http://localhost:9090/api/v1/targets > /dev/null 2>&1; then
    echo "   ✓ Prometheus 运行正常"
    echo ""
    echo "   目标状态："
    curl -s http://localhost:9090/api/v1/targets 2>/dev/null | \
        grep -o '"job":"[^"]*","health":"[^"]*"' | \
        sed 's/"job":"//g; s/","health":"/ -> /g; s/"//g' | \
        awk '{printf "   %-30s %s\n", $1, $3}'
else
    echo "   ✗ Prometheus 未运行"
    echo "   启动命令: docker compose up -d prometheus"
fi

echo ""
echo "=========================================="
echo "  启动服务命令"
echo "=========================================="
echo "  mvn -q -pl gateway spring-boot:run"
echo "  mvn -q -pl order-service spring-boot:run"
echo "  mvn -q -pl payment-service spring-boot:run"
echo "  mvn -q -pl inventory-service spring-boot:run"
echo "  mvn -q -pl user-service spring-boot:run"
echo "  mvn -q -pl outbox-consumer spring-boot:run"
echo "  mvn -q -pl ai-chat-service spring-boot:run"
echo ""
echo "=========================================="
echo "  访问地址"
echo "=========================================="
echo "  Prometheus: http://localhost:9090"
echo "  Grafana:    http://localhost:3000 (admin/admin)"
echo "=========================================="
