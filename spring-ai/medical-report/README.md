# Medical Report Recommendation System

基于 Spring AI 和 DeepSeek API 的医疗报告推荐系统，根据用户问卷和检验报告生成个性化健康建议。

## 功能特性

- **健康建议**：饮食、运动、生活方式、睡眠建议
- **风险评估**：识别健康风险、评估风险等级、提供预防措施
- **就医指导**：推荐就诊科室、评估紧急程度、建议检查项目
- **PDF 解析**：自动提取检验报告内容并整合到分析中
- **智能缓存**：相同问卷自动缓存，提升响应速度
- **历史记录**：自动保存所有报告到数据库，支持查询和统计
- **流式响应**：支持 SSE 流式生成，实时展示生成过程，提升用户体验
- **API 文档**：集成 Swagger UI，访问 `/swagger-ui.html`
- **健康监控**：Actuator 端点监控应用状态

### Spring AI 高级特性 ✨

- **Function Calling**：AI 自动调用 Java 方法查询患者历史报告
- **Structured Output**：使用 BeanOutputConverter 类型安全解析 AI 响应
- **Prompt Template**：模板化管理复杂提示词，支持变量替换
- **Token Counting**：自动统计 Token 使用量和 API 成本
- **Retry & Circuit Breaker**：自动重试和熔断保护，提升系统稳定性

> 详细使用说明请查看 [SPRING_AI_FEATURES.md](./SPRING_AI_FEATURES.md)

## 技术栈

- Java 17
- Spring Boot 3.4.0
- Spring AI 1.1.2
- DeepSeek API
- MySQL 8.0+
- Spring Data JPA
- Lombok
- Apache PDFBox 3.0.1
- SpringDoc OpenAPI 2.3.0

## 快速开始

### 环境配置

设置环境变量：

```bash
export DEEPSEEK_API_KEY="your-deepseek-api-key"
export DEEPSEEK_BASE_URL="https://api.deepseek.com"  # 可选，默认值
export MYSQL_USERNAME="root"  # 可选，默认 root
export MYSQL_PASSWORD="your-mysql-password"  # MySQL 密码
```

### 数据库初始化

首先创建数据库：

```bash
# 连接 MySQL
mysql -u root -p

# 执行初始化脚本
source src/main/resources/db/init.sql
```

或者直接执行：

```bash
mysql -u root -p < src/main/resources/db/init.sql
```

### 启动应用

```bash
cd medical-report
mvn spring-boot:run
```

应用将在 `http://localhost:8083` 启动。

### API 文档

启动应用后访问：
- Swagger UI: `http://localhost:8083/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8083/v3/api-docs`
- 流式生成演示页面: `http://localhost:8083/stream-demo.html`

### 健康检查

```bash
curl http://localhost:8083/actuator/health
```

### API 调用示例

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
    "preferDiet": "重油盐糖，口味偏重",
    "dietary_fiber_intake": "摄入充足，每天都有",
    "digestive_tract_symptoms": ["腹胀"],
    "sportHabits": "几乎不运动（每周运动时间少于30分钟）",
    "physicalActivity": "轻度体力活动",
    "lifeHabits": ["经常熬夜（晚于23点睡觉）"],
    "healthGoals": ["肠胃健康", "睡眠质量", "衰老问题"],
    "femealConditions": ["子宫肌瘤"],
    "antibiotic_recent": "否",
    "stress_high": "否",
    "emotional_eating": "否",
    "medicalRecord": {
      "isExist": false,
      "idcard": "",
      "realName": ""
    },
    "reportFile": {
      "isUpload": true,
      "fileList": [{
        "name": "2948_001.pdf",
        "url": "https://test.slmc.top/api/v1/file/preview?objectName=76269d78eafbf9ef539698cad73deecd.pdf",
        "fileId": "76269d78eafbf9ef539698cad73deecd.pdf",
        "fileType": "pdf"
      }]
    }
  }'
```

### 响应格式

```json
{
  "report": {
    "patientName": "董丽莉",
    "reportDate": "2026-04-14",
    "healthAdvice": [
      "建议减少油盐糖摄入...",
      "建议每周至少150分钟中等强度运动...",
      "改善睡眠习惯，23点前入睡..."
    ],
    "riskAssessment": [
      "代谢综合征风险：中等",
      "PCOS合并肥胖家族史..."
    ],
    "medicalGuidance": [
      "推荐科室：妇科、内分泌科、消化内科",
      "紧急程度：非紧急，建议3个月内就诊",
      "建议检查：性激素六项、糖耐量测试"
    ]
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

## 项目结构

```
medical-report/
├── src/main/java/com/medical/report/
│   ├── MedicalReportApplication.java
│   ├── config/
│   │   └── ChatConfig.java
│   ├── controller/
│   │   └── MedicalReportController.java
│   ├── service/
│   │   └── MedicalReportService.java
│   └── model/
│       ├── QuestionnaireRequest.java
│       └── MedicalReportResponse.java
└── src/main/resources/
    └── application.yml
```

## 配置说明

### application.yml

```yaml
spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com}
      chat:
        options:
          model: deepseek-chat
          temperature: 0.7
```

## 注意事项

1. 确保 `DEEPSEEK_API_KEY` 环境变量已正确设置
2. 确保 MySQL 服务已启动（localhost:3306）
3. 首次启动前需要执行数据库初始化脚本
4. DeepSeek API 调用需要网络连接
5. 生成的报告仅供参考，不能替代专业医疗诊断
6. PDF 检验报告会自动解析并整合到分析中
7. 相同问卷数据会使用缓存，避免重复调用 AI
8. 所有报告自动保存到 MySQL 数据库
9. Hibernate 会自动创建/更新表结构（ddl-auto: update）

## 后续扩展

- [ ] 支持多语言报告生成
- [ ] 集成更多健康数据源
- [ ] 支持 MySQL/PostgreSQL 生产数据库
- [ ] 添加报告导出功能（PDF/Word）
- [ ] 实现 WebSocket 双向通信
