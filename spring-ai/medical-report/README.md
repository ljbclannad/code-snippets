# Medical Report Recommendation System

基于 Spring AI 和 Claude API 的医疗报告推荐系统，根据用户问卷和检验报告生成个性化健康建议。

## 功能特性

- **健康建议**：饮食、运动、生活方式、睡眠建议
- **风险评估**：识别健康风险、评估风险等级、提供预防措施
- **就医指导**：推荐就诊科室、评估紧急程度、建议检查项目

## 技术栈

- Java 17
- Spring Boot 3.4.0
- Spring AI 1.0.0-M4
- Claude API (Anthropic)
- Lombok

## 快速开始

### 环境配置

设置环境变量：

```bash
export ANTHROPIC_BASE_URL="https://api.anthropic.com"
export ANTHROPIC_AUTH_TOKEN="your-api-key"
```

### 启动应用

```bash
cd medical-report
mvn spring-boot:run
```

应用将在 `http://localhost:8083` 启动。

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
  "patientName": "董丽莉",
  "reportDate": "2026-04-14",
  "healthAdvice": {
    "dietaryRecommendations": "建议减少油盐糖摄入...",
    "exerciseRecommendations": "建议每周至少150分钟中等强度运动...",
    "lifestyleRecommendations": "改善睡眠习惯，23点前入睡...",
    "sleepRecommendations": "建立规律作息，避免熬夜..."
  },
  "riskAssessment": {
    "overallRiskLevel": "中等",
    "identifiedRisks": [
      {
        "riskType": "代谢综合征",
        "severity": "中",
        "description": "PCOS合并肥胖家族史..."
      }
    ],
    "preventiveMeasures": "定期体检，控制体重..."
  },
  "medicalGuidance": {
    "recommendedSpecialties": "妇科、内分泌科、消化内科",
    "urgencyLevel": "非紧急，建议3个月内就诊",
    "suggestedTests": "性激素六项、糖耐量测试、妇科超声",
    "followUpAdvice": "每3-6个月复查一次"
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
    anthropic:
      base-url: ${ANTHROPIC_BASE_URL}
      api-key: ${ANTHROPIC_AUTH_TOKEN}
      chat:
        options:
          model: claude-3-5-sonnet-20241022
          temperature: 0.7
          max-tokens: 4096
```

## 注意事项

1. 确保 `ANTHROPIC_BASE_URL` 和 `ANTHROPIC_AUTH_TOKEN` 环境变量已正确设置
2. Claude API 调用需要网络连接
3. 生成的报告仅供参考，不能替代专业医疗诊断
4. 检验报告文件当前仅记录元数据，未实现 PDF 内容解析

## 后续扩展

- [ ] 实现 PDF 检验报告内容提取和分析
- [ ] 添加报告历史记录功能
- [ ] 支持多语言报告生成
- [ ] 集成更多健康数据源
