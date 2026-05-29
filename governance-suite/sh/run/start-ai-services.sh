#!/bin/bash

echo "=== 启动 AI 记忆服务 ==="

# 检查环境变量
if [ -z "$DASHSCOPE_API_KEY" ]; then
    echo "错误: DASHSCOPE_API_KEY 未设置"
    echo "请先执行: export DASHSCOPE_API_KEY=your-key"
    exit 1
fi

if [ -z "$DEEPSEEK_API_KEY" ]; then
    echo "错误: DEEPSEEK_API_KEY 未设置"
    echo "请先执行: export DEEPSEEK_API_KEY=your-key"
    exit 1
fi

if [ -z "$DIFY_API_KEY" ]; then
    echo "错误: DIFY_API_KEY 未设置"
    echo "请先执行: export DIFY_API_KEY=your-key"
    exit 1
fi

# 检查基础设施
echo "检查基础设施状态..."
docker compose ps | grep -q "gov-mysql.*Up" || { echo "MySQL 未启动"; exit 1; }
docker compose ps | grep -q "gov-redis.*Up" || { echo "Redis 未启动"; exit 1; }
docker compose ps | grep -q "gov-rabbitmq.*Up" || { echo "RabbitMQ 未启动"; exit 1; }
docker compose ps | grep -q "gov-nacos.*Up" || { echo "Nacos 未启动"; exit 1; }
docker compose ps | grep -q "gov-milvus.*Up" || { echo "Milvus 未启动"; exit 1; }

echo "基础设施检查通过"

# 创建日志目录
mkdir -p logs

# 启动服务
echo "启动 user-profile-service (8101)..."
mvn -q -pl user-profile-service spring-boot:run > logs/user-profile-service.log 2>&1 &
echo $! > logs/user-profile-service.pid

sleep 10

echo "启动 vector-memory-service (8102)..."
mvn -q -pl vector-memory-service spring-boot:run > logs/vector-memory-service.log 2>&1 &
echo $! > logs/vector-memory-service.pid

sleep 10

echo "启动 ai-chat-service (8103)..."
mvn -q -pl ai-chat-service spring-boot:run > logs/ai-chat-service.log 2>&1 &
echo $! > logs/ai-chat-service.pid

echo ""
echo "=== 启动完成 ==="
echo "user-profile-service: http://localhost:8101"
echo "vector-memory-service: http://localhost:8102"
echo "ai-chat-service: http://localhost:8103"
echo ""
echo "查看日志: tail -f logs/*.log"
echo "停止服务: ./stop-ai-services.sh"
