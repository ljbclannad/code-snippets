#!/bin/bash

echo "=========================================="
echo "  Prometheus + Grafana 监控验证"
echo "=========================================="
echo ""

# 检查容器状态
echo "1. 检查容器状态..."
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep -E "NAMES|prometheus|grafana"
echo ""

# 检查 Prometheus 健康状态
echo "2. 检查 Prometheus 健康状态..."
if curl -s http://localhost:9090/-/healthy > /dev/null 2>&1; then
    echo "   ✓ Prometheus 运行正常 (http://localhost:9090)"
else
    echo "   ✗ Prometheus 未响应"
fi
echo ""

# 检查 Grafana 健康状态
echo "3. 检查 Grafana 健康状态..."
if curl -s http://localhost:3000/api/health > /dev/null 2>&1; then
    echo "   ✓ Grafana 运行正常 (http://localhost:3000)"
    echo "   默认账号: admin / admin"
else
    echo "   ✗ Grafana 未响应"
fi
echo ""

# 检查监控目标
echo "4. 检查 Prometheus 监控目标状态..."
echo "   服务名称                    状态"
echo "   ----------------------------------------"
curl -s http://localhost:9090/api/v1/targets 2>/dev/null | \
    grep -o '"job":"[^"]*","health":"[^"]*"' | \
    sed 's/"job":"//g; s/","health":"/ -> /g; s/"//g' | \
    awk '{printf "   %-30s %s\n", $1, $3}'
echo ""

# 检查 ai-chat-service 是否暴露指标
echo "5. 检查 ai-chat-service 指标端点..."
if curl -s http://localhost:8100/actuator/prometheus > /dev/null 2>&1; then
    echo "   ✓ ai-chat-service 指标端点正常"
    echo "   可用指标数量: $(curl -s http://localhost:8100/actuator/prometheus | grep -c '^ai_')"
else
    echo "   ✗ ai-chat-service 未启动或未暴露指标"
    echo "   启动命令: mvn -q -pl ai/ai-chat-service spring-boot:run"
fi
echo ""

echo "=========================================="
echo "  访问地址"
echo "=========================================="
echo "  Prometheus: http://localhost:9090"
echo "  Grafana:    http://localhost:3000"
echo "  AI Chat:    http://localhost:8100/actuator/prometheus"
echo ""
echo "  下一步："
echo "  1. 启动 ai-chat-service"
echo "  2. 访问 Grafana 查看 'AI Chat Service 监控' 仪表盘"
echo "  3. 发送测试请求产生数据"
echo "=========================================="
