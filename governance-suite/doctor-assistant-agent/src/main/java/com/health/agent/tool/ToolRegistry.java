package com.health.agent.tool;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * 工具注册中心
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new HashMap<>();

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

        // TODO: 从数据库查询
        return Map.of(
            "customerId", customerId,
            "name", "张三",
            "age", 35,
            "gender", "女",
            "height", 165.0,
            "currentWeight", 62.0,
            "targetWeight", 55.0,
            "chronicDiseases", List.of("高血压"),
            "bmi", 22.8
        );
    }

    private Object getWeightHistory(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        Integer days = (Integer) input.getOrDefault("days", 30);
        log.info("查询体重历史: customerId={}, days={}", customerId, days);

        // TODO: 从数据库查询
        return List.of(
            Map.of("date", "2024-01-01", "weight", 65.0),
            Map.of("date", "2024-01-15", "weight", 63.5),
            Map.of("date", "2024-02-01", "weight", 62.0)
        );
    }

    private Object getChatHistory(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        Integer limit = (Integer) input.getOrDefault("limit", 20);
        log.info("查询聊天记录: customerId={}, limit={}", customerId, limit);

        // TODO: 从数据库查询
        return List.of(
            Map.of("date", "2024-02-01", "message", "最近有点焦虑"),
            Map.of("date", "2024-02-02", "message", "是不是方法不对")
        );
    }

    private Object getPrescriptionHistory(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        Integer limit = (Integer) input.getOrDefault("limit", 10);
        log.info("查询处方历史: customerId={}, limit={}", customerId, limit);

        // TODO: 从数据库查询
        return List.of(
            Map.of("date", "2024-01-01", "medication", "降压药", "dosage", "每日一次")
        );
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

        // TODO: 向量检索
        return List.of(
            Map.of("caseId", "C001", "similarity", 0.85, "outcome", "成功减重5kg")
        );
    }

    private Object getHealthKnowledge(Map<String, Object> input) {
        String topic = (String) input.get("topic");
        log.info("查询健康知识: topic={}", topic);

        // TODO: 知识库查询
        return Map.of(
            "topic", topic,
            "content", "相关健康知识内容..."
        );
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

        // TODO: 创建任务
        return Map.of("success", true, "taskId", "T001");
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
