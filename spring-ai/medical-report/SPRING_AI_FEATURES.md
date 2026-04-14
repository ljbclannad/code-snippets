## Spring AI 增强功能使用指南

本项目已集成以下 Spring AI 高级特性：

### 1. Function Calling（函数调用）

AI 可以自动调用 Java 方法查询患者历史报告。

**实现位置**：
- `PatientHistoryFunction.java` - 函数定义
- `ChatConfig.java` - 函数注册

**使用示例**：
```bash
# AI 会自动判断是否需要查询历史
curl -X POST http://localhost:8083/api/medical-report/generate \
  -H "Content-Type: application/json" \
  -d '{
    "name": "张三",
    "age": "45",
    ...
  }'
```

当 AI 需要了解患者历史时，会自动调用 `queryPatientHistory` 函数。

---

### 2. Structured Output（结构化输出）

使用 `BeanOutputConverter` 自动将 AI 响应解析为 Java 对象，无需手动 JSON 解析。

**实现位置**：
- `MedicalReportSchema.java` - 输出结构定义
- `MedicalReportService.java` - 使用 BeanOutputConverter

**优势**：
- 类型安全
- 自动验证
- 减少解析错误

---

### 3. Prompt Template（提示词模板）

将复杂提示词模板化管理，支持变量替换。

**实现位置**：
- `MedicalReportPromptTemplate.java` - 模板管理器
- `src/main/resources/prompts/medical-report-template.txt` - 模板文件

**使用方式**：
```java
String prompt = promptTemplate.createPrompt(request, pdfContent);
```

模板支持变量：`{hasPdf}`, `{questionnaireData}`, `{pdfContent}`

---

### 4. Token Counting & Cost Monitoring（Token 计数与成本监控）

自动统计每次 API 调用的 Token 使用量和成本。

**实现位置**：
- `TokenUsageInfo.java` - Token 统计模型
- `MedicalReportResponseWithMetadata.java` - 响应包装

**响应示例**：
```json
{
  "report": {
    "patientName": "张三",
    "healthAdvice": [...]
  },
  "tokenUsage": {
    "inputTokens": 1250,
    "outputTokens": 850,
    "totalTokens": 2100,
    "estimatedCost": 0.000413,
    "model": "deepseek-chat",
    "responseTimeMs": 3200
  }
}
```

**定价**（DeepSeek）：
- 输入：$0.14/1M tokens
- 输出：$0.28/1M tokens

---

### 5. Retry & Circuit Breaker（重试与熔断器）

自动处理 API 调用失败，提升系统稳定性。

**实现位置**：
- `ResilienceConfig.java` - 配置类
- `application.yml` - 参数配置

**配置说明**：

**Retry（重试）**：
- 最大重试次数：3 次
- 初始等待时间：2 秒
- 指数退避倍数：2（2s → 4s → 8s）

**Circuit Breaker（熔断器）**：
- 失败率阈值：50%
- 滑动窗口大小：10 次调用
- 最小调用次数：5 次
- 熔断等待时间：30 秒

**工作流程**：
1. 正常调用 DeepSeek API
2. 失败时自动重试（最多 3 次）
3. 失败率超过 50% 时触发熔断
4. 熔断期间直接返回错误，不调用 API
5. 30 秒后进入半开状态，尝试恢复

---

## 监控端点

查看熔断器状态：
```bash
curl http://localhost:8083/actuator/health
```

查看应用指标：
```bash
curl http://localhost:8083/actuator/metrics
```

---

## 完整调用示例

```bash
curl -X POST http://localhost:8083/api/medical-report/generate \
  -H "Content-Type: application/json" \
  -d '{
    "name": "董丽莉",
    "age": "51",
    "gender": "女",
    "weight": "60",
    "height": "160",
    "bmi": "23.44",
    "familyDiseases": ["肥胖"],
    "conditions": ["PCOS 多囊卵巢综合症"],
    "healthGoals": ["肠胃健康", "睡眠质量"]
  }'
```

**响应包含**：
- 完整医疗报告
- Token 使用统计
- 成本估算
- 响应时间

---

## 技术栈总结

| 功能 | 技术 | 说明 |
|------|------|------|
| Function Calling | Spring AI | AI 自动调用 Java 方法 |
| Structured Output | BeanOutputConverter | 类型安全的输出解析 |
| Prompt Template | PromptTemplate | 模板化提示词管理 |
| Token Counting | ChatResponse Metadata | 自动统计 Token 使用 |
| Retry | Spring Retry | 自动重试失败请求 |
| Circuit Breaker | Resilience4j | 熔断保护 |
| Streaming | SSE | 流式响应 |
| Caching | Spring Cache | 智能缓存 |

---

## 下一步优化建议

1. **RAG（检索增强生成）**：构建医疗知识库向量检索
2. **Chat Memory**：支持多轮对话上下文
3. **Batch Processing**：批量生成报告
4. **Async Processing**：异步任务队列
5. **Rate Limiting**：API 调用频率限制
