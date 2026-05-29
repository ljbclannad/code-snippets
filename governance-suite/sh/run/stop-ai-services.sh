#!/bin/bash

echo "=== 停止 AI 记忆服务 ==="

for service in user-profile-service vector-memory-service ai-chat-service; do
    if [ -f logs/${service}.pid ]; then
        pid=$(cat logs/${service}.pid)
        echo "停止 ${service} (PID: ${pid})..."
        kill $pid 2>/dev/null || echo "${service} 已停止"
        rm logs/${service}.pid
    fi
done

echo "=== 停止完成 ==="
