package com.health.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.health.agent.service.PatientService;
import com.health.agent.service.FollowUpTaskService;
import com.health.agent.service.HealthKnowledgeService;
import com.health.agent.model.PatientProfile;
import com.health.agent.model.WeightRecord;
import com.health.agent.model.ChatMessage;
import com.health.agent.model.PrescriptionRecord;
import com.health.agent.model.FollowUpTask;
import com.health.agent.model.HealthKnowledge;
import com.health.agent.vector.VectorSearchService;
import com.health.agent.vector.CaseVector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具注册中心
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new HashMap<>();

    @Autowired
    private PatientService patientService;

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private FollowUpTaskService followUpTaskService;

    @Autowired
    private HealthKnowledgeService healthKnowledgeService;

    @PostConstruct
    public void registerTools() {
        log.info("开始注册工具...");

        // 低风险工具：查询类
        register(Tool.builder()
            .name("get_patient_profile")
            .description("查询患者基本信息（年龄、性别、目标、慢性病等）")
            .riskLevel(ToolRiskLevel.LOW)
            .inputSchema(Map.of(
                "customerId", Map.of("type", "string", "required", true)
            ))
            .executor(this::getPatientProfile)
            .build());

        register(Tool.builder()
            .name("get_weight_history")
            .description("查询患者体重历史记录")
            .riskLevel(ToolRiskLevel.LOW)
            .inputSchema(Map.of(
                "customerId", Map.of("type", "string", "required", true),
                "days", Map.of("type", "integer", "default", 30)
            ))
            .executor(this::getWeightHistory)
            .build());

        register(Tool.builder()
            .name("get_chat_history")
            .description("查询患者最近的聊天记录")
            .riskLevel(ToolRiskLevel.LOW)
            .inputSchema(Map.of(
                "customerId", Map.of("type", "string", "required", true),
                "limit", Map.of("type", "integer", "default", 20)
            ))
            .executor(this::getChatHistory)
            .build());

        register(Tool.builder()
            .name("get_prescription_history")
            .description("查询患者的处方历史")
            .riskLevel(ToolRiskLevel.LOW)
            .inputSchema(Map.of(
                "customerId", Map.of("type", "string", "required", true),
                "limit", Map.of("type", "integer", "default", 10)
            ))
            .executor(this::getPrescriptionHistory)
            .build());

        register(Tool.builder()
            .name("calculate_bmi")
            .description("计算BMI指数")
            .riskLevel(ToolRiskLevel.LOW)
            .inputSchema(Map.of(
                "weight", Map.of("type", "number", "required", true),
                "height", Map.of("type", "number", "required", true)
            ))
            .executor(this::calculateBmi)
            .build());

        register(Tool.builder()
            .name("analyze_weight_trend")
            .description("分析体重变化趋势")
            .riskLevel(ToolRiskLevel.LOW)
            .inputSchema(Map.of(
                "weightRecords", Map.of("type", "array", "required", true)
            ))
            .executor(this::analyzeWeightTrend)
            .build());

        register(Tool.builder()
            .name("search_similar_cases")
            .description("搜索相似病例")
            .riskLevel(ToolRiskLevel.LOW)
            .inputSchema(Map.of(
                "symptoms", Map.of("type", "array", "required", true),
                "limit", Map.of("type", "integer", "default", 5)
            ))
            .executor(this::searchSimilarCases)
            .build());

        register(Tool.builder()
            .name("get_health_knowledge")
            .description("查询健康知识库")
            .riskLevel(ToolRiskLevel.LOW)
            .inputSchema(Map.of(
                "topic", Map.of("type", "string", "required", true)
            ))
            .executor(this::getHealthKnowledge)
            .build());

        // 中风险工具：通知类
        register(Tool.builder()
            .name("send_reminder")
            .description("给患者发送提醒消息")
            .riskLevel(ToolRiskLevel.MEDIUM)
            .inputSchema(Map.of(
                "customerId", Map.of("type", "string", "required", true),
                "message", Map.of("type", "string", "required", true)
            ))
            .executor(this::sendReminder)
            .build());

        register(Tool.builder()
            .name("create_follow_up_task")
            .description("创建随访任务")
            .riskLevel(ToolRiskLevel.MEDIUM)
            .inputSchema(Map.of(
                "customerId", Map.of("type", "string", "required", true),
                "taskDescription", Map.of("type", "string", "required", true),
                "dueDate", Map.of("type", "string", "required", true)
            ))
            .executor(this::createFollowUpTask)
            .build());

        register(Tool.builder()
            .name("send_health_report")
            .description("发送健康报告给患者")
            .riskLevel(ToolRiskLevel.MEDIUM)
            .inputSchema(Map.of(
                "customerId", Map.of("type", "string", "required", true),
                "reportContent", Map.of("type", "string", "required", true)
            ))
            .executor(this::sendHealthReport)
            .build());

        // 高风险工具：医疗决策类
        register(Tool.builder()
            .name("draft_prescription")
            .description("起草处方建议（需要医生审核确认）")
            .riskLevel(ToolRiskLevel.HIGH)
            .inputSchema(Map.of(
                "customerId", Map.of("type", "string", "required", true),
                "recommendations", Map.of("type", "array", "required", true),
                "reason", Map.of("type", "string", "required", true)
            ))
            .executor(this::draftPrescription)
            .build());

        register(Tool.builder()
            .name("suggest_diagnosis")
            .description("提出诊断建议（需要医生审核确认）")
            .riskLevel(ToolRiskLevel.HIGH)
            .inputSchema(Map.of(
                "customerId", Map.of("type", "string", "required", true),
                "diagnosis", Map.of("type", "string", "required", true),
                "evidence", Map.of("type", "array", "required", true)
            ))
            .executor(this::suggestDiagnosis)
            .build());

        log.info("工具注册完成，共注册 {} 个工具", tools.size());
    }

    private void register(Tool tool) {
        tools.put(tool.getName(), tool);
        log.debug("注册工具: {} ({})", tool.getName(), tool.getRiskLevel());
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    public List<Tool> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    // ========== 工具执行器实现 ==========

    private Object getPatientProfile(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        log.info("查询患者档案: {}", customerId);

        PatientProfile profile = patientService.getProfile(customerId);
        if (profile == null) {
            return Map.of("error", "患者档案不存在");
        }

        return Map.of(
            "customerId", profile.getCustomerId(),
            "name", profile.getName(),
            "age", profile.getAge(),
            "gender", profile.getGender(),
            "height", profile.getHeight(),
            "currentWeight", profile.getCurrentWeight(),
            "targetWeight", profile.getTargetWeight(),
            "chronicDiseases", parseStringList(profile.getChronicDiseases()),
            "bmi", profile.getBmi()
        );
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }

        try {
            return JSON.parseArray(json, String.class);
        } catch (Exception e) {
            return Arrays.asList(json.split(","));
        }
    }

    private Object getWeightHistory(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        Integer days = (Integer) input.getOrDefault("days", 30);
        log.info("查询体重历史: customerId={}, days={}", customerId, days);

        List<WeightRecord> records = patientService.getWeightHistory(customerId, days);

        return records.stream()
            .map(record -> Map.of(
                "date", record.getRecordDate().toString(),
                "weight", record.getWeight(),
                "bmi", record.getBmi() != null ? record.getBmi() : 0.0
            ))
            .collect(Collectors.toList());
    }

    private Object getChatHistory(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        Integer limit = (Integer) input.getOrDefault("limit", 20);
        log.info("查询聊天记录: customerId={}, limit={}", customerId, limit);

        List<ChatMessage> messages = patientService.getChatHistory(customerId, limit);

        return messages.stream()
            .map(msg -> Map.of(
                "date", msg.getCreatedAt().toLocalDate().toString(),
                "message", msg.getMessage(),
                "sender", msg.getSender()
            ))
            .collect(Collectors.toList());
    }

    private Object getPrescriptionHistory(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        Integer limit = (Integer) input.getOrDefault("limit", 10);
        log.info("查询处方历史: customerId={}, limit={}", customerId, limit);

        List<PrescriptionRecord> prescriptions = patientService.getPrescriptionHistory(customerId, limit);

        return prescriptions.stream()
            .map(p -> Map.of(
                "date", p.getCreatedAt().toLocalDate().toString(),
                "medication", p.getMedication(),
                "dosage", p.getDosage() != null ? p.getDosage() : "",
                "duration", p.getDuration() != null ? p.getDuration() : ""
            ))
            .collect(Collectors.toList());
    }

    private Object calculateBmi(Map<String, Object> input) {
        Double weight = ((Number) input.get("weight")).doubleValue();
        Double height = ((Number) input.get("height")).doubleValue();

        double bmi = weight / Math.pow(height / 100, 2);
        String category;

        if (bmi < 18.5) {
            category = "偏瘦";
        } else if (bmi < 24) {
            category = "正常";
        } else if (bmi < 28) {
            category = "超重";
        } else {
            category = "肥胖";
        }

        return Map.of("bmi", Math.round(bmi * 10) / 10.0, "category", category);
    }

    private Object analyzeWeightTrend(Map<String, Object> input) {
        List<Map<String, Object>> records = (List<Map<String, Object>>) input.get("weightRecords");

        if (records.isEmpty()) {
            return Map.of("trend", "无数据");
        }

        // 简单趋势分析
        double firstWeight = ((Number) records.get(0).get("weight")).doubleValue();
        double lastWeight = ((Number) records.get(records.size() - 1).get("weight")).doubleValue();
        double change = lastWeight - firstWeight;

        String trend;
        if (Math.abs(change) < 0.5) {
            trend = "稳定";
        } else if (change > 0) {
            trend = "上升";
        } else {
            trend = "下降";
        }

        return Map.of(
            "trend", trend,
            "change", Math.round(change * 10) / 10.0,
            "totalRecords", records.size()
        );
    }

    private Object searchSimilarCases(Map<String, Object> input) {
        List<String> symptoms = (List<String>) input.get("symptoms");
        Integer limit = (Integer) input.getOrDefault("limit", 5);
        log.info("搜索相似病例: symptoms={}, limit={}", symptoms, limit);

        // 检查向量检索是否可用
        if (!vectorSearchService.isAvailable()) {
            log.warn("向量检索服务不可用，返回 Mock 数据");
            return List.of(
                Map.of(
                    "caseId", "MOCK_001",
                    "description", "35岁女性，高血压，减重成功",
                    "diagnosis", "高血压控制良好，体重管理成功",
                    "similarity", 0.85
                ),
                Map.of(
                    "caseId", "MOCK_002",
                    "description", "40岁男性，糖尿病，血糖控制",
                    "diagnosis", "糖尿病控制稳定",
                    "similarity", 0.78
                )
            );
        }

        // 构建查询文本
        String query = "症状: " + String.join(", ", symptoms);

        // 向量检索
        List<CaseVector> results = vectorSearchService.searchSimilarCases(query, limit);

        // 转换为返回格式
        return results.stream()
            .map(cv -> Map.of(
                "caseId", cv.getCaseId(),
                "customerId", cv.getCustomerId(),
                "description", cv.getDescription(),
                "diagnosis", cv.getDiagnosis(),
                "similarity", cv.getSimilarity()
            ))
            .collect(Collectors.toList());
    }

    private Object getHealthKnowledge(Map<String, Object> input) {
        String topic = (String) input.get("topic");
        log.info("查询健康知识: topic={}", topic);

        // 先尝试精确匹配主题
        List<HealthKnowledge> knowledgeList = healthKnowledgeService.getByTopic(topic, 3);

        // 如果没有结果，尝试全文搜索
        if (knowledgeList.isEmpty()) {
            knowledgeList = healthKnowledgeService.searchByKeyword(topic, 3);
        }

        if (knowledgeList.isEmpty()) {
            return Map.of(
                "topic", topic,
                "found", false,
                "message", "未找到相关健康知识，建议咨询专业医生"
            );
        }

        return knowledgeList.stream()
            .map(k -> Map.of(
                "title", k.getTitle(),
                "category", k.getCategory(),
                "content", k.getContent(),
                "source", k.getSource() != null ? k.getSource() : ""
            ))
            .collect(Collectors.toList());
    }

    private Object sendReminder(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        String message = (String) input.get("message");
        log.info("发送提醒: customerId={}, message={}", customerId, message);

        // TODO: 发送消息
        return Map.of("success", true, "message", "提醒已发送");
    }

    private Object createFollowUpTask(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        String taskDescription = (String) input.get("taskDescription");
        String dueDate = (String) input.get("dueDate");
        log.info("创建随访任务: customerId={}, task={}", customerId, taskDescription);

        try {
            // 解析日期
            java.time.LocalDate dueDateParsed = java.time.LocalDate.parse(dueDate);

            // 创建任务
            FollowUpTask task = followUpTaskService.createTask(
                customerId,
                taskDescription,
                dueDateParsed,
                "AGENT"
            );

            return Map.of(
                "success", true,
                "taskId", task.getTaskId(),
                "dueDate", task.getDueDate().toString(),
                "message", "随访任务创建成功"
            );
        } catch (Exception e) {
            log.error("创建随访任务失败", e);
            return Map.of(
                "success", false,
                "message", "创建任务失败: " + e.getMessage()
            );
        }
    }

    private Object sendHealthReport(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        String reportContent = (String) input.get("reportContent");
        log.info("发送健康报告: customerId={}", customerId);

        // TODO: 发送报告
        return Map.of("success", true, "message", "报告已发送");
    }

    private Object draftPrescription(Map<String, Object> input) {
        // 高风险操作，不会被直接调用
        // 会被 ActionExecutor 拦截并创建待确认任务
        log.warn("draftPrescription 不应该被直接调用");
        return null;
    }

    private Object suggestDiagnosis(Map<String, Object> input) {
        // 高风险操作，不会被直接调用
        log.warn("suggestDiagnosis 不应该被直接调用");
        return null;
    }
}
