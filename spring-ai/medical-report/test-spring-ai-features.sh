#!/bin/bash

# Spring AI 功能测试脚本
BASE_URL="http://localhost:8083"

echo "=========================================="
echo "Spring AI 功能增强测试"
echo "=========================================="
echo ""

# 1. 健康检查
echo "1. 健康检查..."
curl -s "$BASE_URL/actuator/health" | jq '.'
echo ""

# 2. 查看已启用功能
echo "2. 查看已启用的 Spring AI 功能..."
curl -s "$BASE_URL/api/demo/features" | jq '.'
echo ""

# 3. 测试完整报告生成（包含 Token 统计）
echo "3. 测试报告生成（带 Token 统计）..."
curl -s -X POST "$BASE_URL/api/medical-report/generate" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "测试患者",
    "age": "45",
    "gender": "女",
    "weight": "65",
    "height": "165",
    "bmi": "23.9",
    "familyDiseases": ["高血压"],
    "conditions": ["糖尿病"],
    "healthGoals": ["血糖控制", "体重管理"]
  }' | jq '{
    patientName: .report.patientName,
    reportDate: .report.reportDate,
    healthAdviceCount: (.report.healthAdvice | length),
    tokenUsage: .tokenUsage
  }'
echo ""

# 4. 测试 Function Calling
echo "4. 测试 Function Calling（AI 自动查询历史）..."
curl -s -X POST "$BASE_URL/api/demo/function-calling" | jq '{
    patientName: .report.patientName,
    tokenUsage: .tokenUsage
  }'
echo ""

# 5. 查看熔断器状态
echo "5. 查看熔断器和重试配置状态..."
curl -s "$BASE_URL/actuator/health" | jq '.components.circuitBreakers // "Circuit breaker info not available"'
echo ""

# 6. 测试历史查询
echo "6. 查询最近的报告历史..."
curl -s "$BASE_URL/api/medical-report-history/recent?limit=3" | jq 'map({
    id: .id,
    patientName: .patientName,
    reportDate: .reportDate
  })'
echo ""

echo "=========================================="
echo "测试完成！"
echo "=========================================="
echo ""
echo "功能说明："
echo "✅ Function Calling - AI 自动调用 Java 方法"
echo "✅ Structured Output - 类型安全的输出解析"
echo "✅ Prompt Template - 模板化提示词管理"
echo "✅ Token Counting - 自动统计使用量和成本"
echo "✅ Retry & Circuit Breaker - 容错保护"
echo ""
echo "详细文档: SPRING_AI_FEATURES.md"
