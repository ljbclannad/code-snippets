package com.health.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.health.agent.engine.AgentToolCatalog;
import com.health.agent.scene.doctor.tool.DoctorToolDefinitionRegistry;
import com.health.agent.service.PatientService;
import com.health.agent.service.FollowUpTaskService;
import com.health.agent.service.HealthKnowledgeService;
import com.health.agent.service.WeightPlateauService;
import com.health.agent.service.AdherenceAnalysisService;
import com.health.agent.model.PatientProfile;
import com.health.agent.model.WeightRecord;
import com.health.agent.model.ChatMessage;
import com.health.agent.model.PrescriptionRecord;
import com.health.agent.model.FollowUpTask;
import com.health.agent.model.HealthKnowledge;
import com.health.agent.model.ToolEvidence;
import com.health.agent.model.ToolExecutionOutput;
import com.lx.governance.agentplatform.model.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具注册中心
 */
@Slf4j
@Component
public class ToolRegistry implements AgentToolCatalog {

    private final Map<String, Tool> tools = new HashMap<>();
    private final DoctorToolDefinitionRegistry doctorToolDefinitionRegistry;

    public ToolRegistry(DoctorToolDefinitionRegistry doctorToolDefinitionRegistry) {
        this.doctorToolDefinitionRegistry = doctorToolDefinitionRegistry;
    }

    @Autowired
    private PatientService patientService;

    @Autowired
    private FollowUpTaskService followUpTaskService;

    @Autowired
    private HealthKnowledgeService healthKnowledgeService;

    @Autowired
    private WeightPlateauService weightPlateauService;

    @Autowired
    private AdherenceAnalysisService adherenceAnalysisService;

    @PostConstruct
    public void registerTools() {
        log.info("开始注册工具...");
        tools.clear();
        doctorToolDefinitionRegistry.listDefinitions().forEach(this::registerDefinition);

        log.info("工具注册完成，共注册 {} 个工具", tools.size());
    }

    private void registerDefinition(ToolDefinition definition) {
        register(Tool.builder()
                .name(definition.getToolName())
                .description(definition.getDescription())
                .riskLevel(resolveRiskLevel(definition.getRiskLevel()))
                .inputSchema(definition.getSchema())
                .executor(resolveExecutor(definition.getToolName()))
                .build());
    }

    private void register(Tool tool) {
        tools.put(tool.getName(), tool);
        log.debug("注册工具: {} ({})", tool.getName(), tool.getRiskLevel());
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    public ToolDefinition getDefinition(String name) {
        return doctorToolDefinitionRegistry.findDefinition(name).orElse(null);
    }

    public List<Tool> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    @Override
    public List<com.lx.governance.agentplatform.model.ToolDescriptor> listToolDescriptors() {
        return doctorToolDefinitionRegistry.listToolDescriptors();
    }

    private ToolRiskLevel resolveRiskLevel(String riskLevel) {
        if (riskLevel == null || riskLevel.isBlank()) {
            return ToolRiskLevel.LOW;
        }
        try {
            return ToolRiskLevel.valueOf(riskLevel);
        } catch (IllegalArgumentException ignore) {
            return ToolRiskLevel.LOW;
        }
    }

    private ToolExecutor resolveExecutor(String toolName) {
        return switch (toolName) {
            case "get_patient_profile" -> this::getPatientProfile;
            case "get_weight_history" -> this::getWeightHistory;
            case "get_chat_history" -> this::getChatHistory;
            case "get_prescription_history" -> this::getPrescriptionHistory;
            case "calculate_bmi" -> this::calculateBmi;
            case "analyze_weight_trend" -> this::analyzeWeightTrend;
            case "detect_weight_plateau" -> this::detectWeightPlateau;
            case "analyze_adherence_signals" -> this::analyzeAdherenceSignals;
            case "get_health_knowledge" -> this::getHealthKnowledge;
            case "search_medical_knowledge" -> this::searchMedicalKnowledge;
            case "search_medication_guidance" -> this::searchMedicationGuidance;
            case "search_follow_up_protocol" -> this::searchFollowUpProtocol;
            case "search_red_flag_guideline" -> this::searchRedFlagGuideline;
            case "triage_red_flags" -> this::triageRedFlags;
            case "analyze_medication_adherence" -> this::analyzeMedicationAdherence;
            case "summarize_symptom_timeline" -> this::summarizeSymptomTimeline;
            case "assess_follow_up_urgency" -> this::assessFollowUpUrgency;
            case "generate_patient_education_points" -> this::generatePatientEducationPoints;
            case "check_self_monitoring_completeness" -> this::checkSelfMonitoringCompleteness;
            case "extract_structured_symptoms" -> this::extractStructuredSymptoms;
            case "screen_mental_health_risk" -> this::screenMentalHealthRisk;
            case "recommend_follow_up_data_requests" -> this::recommendFollowUpDataRequests;
            case "send_reminder" -> this::sendReminder;
            case "create_follow_up_task" -> this::createFollowUpTask;
            case "send_health_report" -> this::sendHealthReport;
            case "draft_weight_management_plan" -> this::draftWeightManagementPlan;
            case "recommend_medical_review" -> this::recommendMedicalReview;
            default -> throw new IllegalStateException("未配置工具执行器: " + toolName);
        };
    }

    // ========== 工具执行器实现 ==========

    private Object getPatientProfile(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        log.info("查询患者档案: {}", customerId);

        PatientProfile profile = patientService.getProfile(customerId);
        if (profile == null) {
            return ToolExecutionOutput.builder()
                .data(Map.of("error", "患者档案不存在"))
                .summary("未找到患者档案")
                .stateUpdates(Map.of("patient_profile", Map.of("error", "患者档案不存在")))
                .evidence(List.of(
                    ToolEvidence.builder()
                        .source("PATIENT_PROFILE")
                        .summary("患者档案不存在")
                        .payload(Map.of("customerId", customerId))
                        .build()
                ))
                .build();
        }

        Map<String, Object> data = Map.of(
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
        return ToolExecutionOutput.builder()
            .data(data)
            .summary("已获取患者基础档案")
            .stateUpdates(Map.of("patient_profile", data))
            .evidence(List.of(
                ToolEvidence.builder()
                    .source("PATIENT_PROFILE")
                    .summary("已获取患者基础档案")
                    .payload(data)
                    .build()
            ))
            .build();
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

        List<Map<String, Object>> data = records.stream()
            .map(record -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", record.getRecordDate().toString());
                row.put("weight", record.getWeight());
                row.put("bmi", record.getBmi() != null ? record.getBmi() : 0.0);
                return row;
            })
            .collect(Collectors.toList());
        return ToolExecutionOutput.builder()
            .data(data)
            .summary("已获取近%d天体重记录".formatted(days))
            .stateUpdates(Map.of("weight_history", data))
            .evidence(List.of(
                ToolEvidence.builder()
                    .source("WEIGHT_HISTORY")
                    .summary("已获取近%d天体重记录".formatted(days))
                    .payload(data)
                    .build()
            ))
            .build();
    }

    private Object getChatHistory(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        Integer limit = (Integer) input.getOrDefault("limit", 20);
        log.info("查询聊天记录: customerId={}, limit={}", customerId, limit);

        List<ChatMessage> messages = patientService.getChatHistory(customerId, limit);

        List<Map<String, Object>> data = messages.stream()
            .map(msg -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", msg.getCreatedAt().toLocalDate().toString());
                row.put("message", msg.getMessage());
                row.put("sender", msg.getSender());
                return row;
            })
            .collect(Collectors.toList());
        return ToolExecutionOutput.builder()
            .data(data)
            .summary("已获取最近%d条聊天记录".formatted(limit))
            .stateUpdates(Map.of("chat_history", data))
            .evidence(List.of(
                ToolEvidence.builder()
                    .source("CHAT_HISTORY")
                    .summary("已获取最近%d条聊天记录".formatted(limit))
                    .payload(data)
                    .build()
            ))
            .build();
    }

    private Object getPrescriptionHistory(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        Integer limit = (Integer) input.getOrDefault("limit", 10);
        log.info("查询处方历史: customerId={}, limit={}", customerId, limit);

        List<PrescriptionRecord> prescriptions = patientService.getPrescriptionHistory(customerId, limit);

        List<Map<String, Object>> data = prescriptions.stream()
            .map(p -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", p.getCreatedAt().toLocalDate().toString());
                row.put("medication", p.getMedication());
                row.put("dosage", p.getDosage() != null ? p.getDosage() : "");
                row.put("duration", p.getDuration() != null ? p.getDuration() : "");
                return row;
            })
            .collect(Collectors.toList());
        return ToolExecutionOutput.builder()
            .data(data)
            .summary("已获取最近%d条处方记录".formatted(limit))
            .stateUpdates(Map.of("prescription_history", data))
            .evidence(List.of(
                ToolEvidence.builder()
                    .source("PRESCRIPTION_HISTORY")
                    .summary("已获取最近%d条处方记录".formatted(limit))
                    .payload(data)
                    .build()
            ))
            .build();
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

        Map<String, Object> data = Map.of("bmi", Math.round(bmi * 10) / 10.0, "category", category);
        return ToolExecutionOutput.builder()
            .data(data)
            .summary("已完成BMI计算")
            .stateUpdates(Map.of("bmi_assessment", data))
            .evidence(List.of(
                ToolEvidence.builder()
                    .source("CALCULATION")
                    .summary("已完成BMI计算")
                    .payload(data)
                    .build()
            ))
            .build();
    }

    private Object analyzeWeightTrend(Map<String, Object> input) {
        List<Map<String, Object>> records = (List<Map<String, Object>>) input.get("weightRecords");

        if (records.isEmpty()) {
            return ToolExecutionOutput.builder()
                .data(Map.of("trend", "无数据"))
                .summary("体重记录为空，无法分析趋势")
                .stateUpdates(Map.of("weight_trend_analysis", Map.of("trend", "无数据")))
                .evidence(List.of(
                    ToolEvidence.builder()
                        .source("WEIGHT_HISTORY")
                        .summary("体重记录为空，无法分析趋势")
                        .payload(Map.of("trend", "无数据"))
                        .build()
                ))
                .build();
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

        Map<String, Object> data = Map.of(
            "trend", trend,
            "change", Math.round(change * 10) / 10.0,
            "totalRecords", records.size(),
            "evidence", List.of(
                "起始体重 %.1fkg，当前体重 %.1fkg".formatted(firstWeight, lastWeight),
                "共分析 %d 条体重记录".formatted(records.size())
            ),
            "evidenceSources", List.of("WEIGHT_HISTORY")
        );
        return ToolExecutionOutput.builder()
            .data(data)
            .summary("已完成体重趋势分析")
            .stateUpdates(Map.of("weight_trend_analysis", data))
            .evidence(List.of(
                ToolEvidence.builder()
                    .source("WEIGHT_HISTORY")
                    .summary("已完成体重趋势分析")
                    .payload(data)
                    .build()
            ))
            .build();
    }

    private Object detectWeightPlateau(Map<String, Object> input) {
        List<Map<String, Object>> records = (List<Map<String, Object>>) input.get("weightRecords");
        Map<String, Object> data = weightPlateauService.detectPlateau(records);
        return ToolExecutionOutput.builder()
            .data(data)
            .summary("已完成平台期判断")
            .stateUpdates(Map.of("plateau_assessment", data))
            .evidence(List.of(
                ToolEvidence.builder()
                    .source("WEIGHT_HISTORY")
                    .summary("已完成平台期判断")
                    .payload(data)
                    .build()
            ))
            .build();
    }

    private Object analyzeAdherenceSignals(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        Integer recentDays = (Integer) input.getOrDefault("recentDays", 14);
        Integer taskLimit = (Integer) input.getOrDefault("taskLimit", 10);

        List<WeightRecord> weightRecords = patientService.getWeightHistory(customerId, recentDays);
        List<ChatMessage> chatMessages = patientService.getChatHistory(customerId, 20);
        List<FollowUpTask> tasks = followUpTaskService.getTasksByCustomerId(customerId, taskLimit);

        Map<String, Object> data = adherenceAnalysisService.analyze(customerId, weightRecords, chatMessages, tasks);
        return ToolExecutionOutput.builder()
            .data(data)
            .summary("已完成依从性分析")
            .stateUpdates(Map.of("adherence_analysis", data))
            .evidence(List.of(
                ToolEvidence.builder()
                    .source("CHAT_HISTORY")
                    .summary("已完成依从性分析")
                    .payload(data)
                    .build()
            ))
            .build();
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

        return buildKnowledgeToolOutput("health_knowledge", topic, knowledgeList, "相关健康知识");
    }

    private Object searchMedicalKnowledge(Map<String, Object> input) {
        String query = (String) input.get("query");
        log.info("查询医疗知识: query={}", query);
        return buildKnowledgeToolOutput(
                "medical_knowledge",
                query,
                healthKnowledgeService.searchMedicalKnowledge(query, 5),
                "医疗知识");
    }

    private Object searchMedicationGuidance(Map<String, Object> input) {
        String query = (String) input.get("query");
        log.info("查询用药指导: query={}", query);
        return buildKnowledgeToolOutput(
                "medication_guidance",
                query,
                healthKnowledgeService.searchMedicationGuidance(query, 5),
                "用药指导");
    }

    private Object searchFollowUpProtocol(Map<String, Object> input) {
        String query = (String) input.get("query");
        log.info("查询随访规范: query={}", query);
        return buildKnowledgeToolOutput(
                "follow_up_protocol",
                query,
                healthKnowledgeService.searchFollowUpProtocol(query, 5),
                "随访规范");
    }

    private Object searchRedFlagGuideline(Map<String, Object> input) {
        String query = (String) input.get("query");
        log.info("查询红旗症状指引: query={}", query);
        return buildKnowledgeToolOutput(
                "red_flag_guideline",
                query,
                healthKnowledgeService.searchRedFlagGuideline(query, 5),
                "红旗症状指引");
    }

    private Object sendReminder(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        String message = (String) input.get("message");
        log.info("发送提醒: customerId={}, message={}", customerId, message);

        // TODO: 发送消息
        Map<String, Object> data = Map.of("success", true, "message", "提醒已发送");
        return ToolExecutionOutput.builder()
            .data(data)
            .summary("已发送提醒消息")
            .stateUpdates(Map.of("reminder_result", data))
            .evidence(List.of(
                ToolEvidence.builder()
                    .source("FOLLOW_UP_TASK")
                    .summary("已发送提醒消息")
                    .payload(Map.of("customerId", customerId, "message", message))
                    .build()
            ))
            .build();
    }

    private Object buildKnowledgeToolOutput(
            String stateSlot,
            String query,
            List<HealthKnowledge> knowledgeList,
            String knowledgeType) {
        if (knowledgeList == null || knowledgeList.isEmpty()) {
            Map<String, Object> data = Map.of(
                    "query", query,
                    "found", false,
                    "message", "未找到相关" + knowledgeType + "，建议结合患者情况进一步确认");
            return ToolExecutionOutput.builder()
                    .data(data)
                    .summary("未找到相关" + knowledgeType)
                    .stateUpdates(Map.of(stateSlot, data))
                    .evidence(List.of(
                            ToolEvidence.builder()
                                    .source("HEALTH_KNOWLEDGE")
                                    .summary("未找到相关" + knowledgeType)
                                    .payload(data)
                                    .build()))
                    .build();
        }

        List<Map<String, Object>> data = knowledgeList.stream()
                .map(k -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("title", k.getTitle());
                    row.put("category", k.getCategory());
                    row.put("content", k.getContent());
                    row.put("source", k.getSource() != null ? k.getSource() : "");
                    row.put("trustLevel", k.getTrustLevel() != null ? k.getTrustLevel() : "");
                    row.put("hitReason", k.getHitReason() != null ? k.getHitReason() : "");
                    row.put("score", k.getScore());
                    row.put("applicableScope", k.getApplicableScope() != null ? k.getApplicableScope() : "");
                    return row;
                })
                .collect(Collectors.toList());
        return ToolExecutionOutput.builder()
                .data(data)
                .summary("已检索到相关" + knowledgeType)
                .stateUpdates(Map.of(stateSlot, data))
                .evidence(List.of(
                        ToolEvidence.builder()
                                .source("HEALTH_KNOWLEDGE")
                                .summary("已检索到相关" + knowledgeType)
                                .payload(data)
                                .build()))
                .build();
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

            Map<String, Object> data = Map.of(
                "success", true,
                "taskId", task.getTaskId(),
                "dueDate", task.getDueDate().toString(),
                "message", "随访任务创建成功"
            );
            return ToolExecutionOutput.builder()
                .data(data)
                .summary("已创建随访任务")
                .stateUpdates(Map.of("follow_up_tasks", List.of(data)))
                .evidence(List.of(
                    ToolEvidence.builder()
                        .source("FOLLOW_UP_TASK")
                        .summary("已创建随访任务")
                        .payload(data)
                        .build()
                ))
                .build();
        } catch (Exception e) {
            log.error("创建随访任务失败", e);
            Map<String, Object> data = Map.of(
                "success", false,
                "message", "创建任务失败: " + e.getMessage()
            );
            return ToolExecutionOutput.builder()
                .data(data)
                .summary("创建随访任务失败")
                .stateUpdates(Map.of("follow_up_task_error", data))
                .evidence(List.of(
                    ToolEvidence.builder()
                        .source("FOLLOW_UP_TASK")
                        .summary("创建随访任务失败")
                        .payload(data)
                        .build()
                ))
                .build();
        }
    }

    private Object sendHealthReport(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        String reportContent = (String) input.get("reportContent");
        log.info("发送健康报告: customerId={}", customerId);

        // TODO: 发送报告
        Map<String, Object> data = Map.of("success", true, "message", "报告已发送");
        return ToolExecutionOutput.builder()
            .data(data)
            .summary("已发送健康报告")
            .stateUpdates(Map.of("health_report_result", data))
            .evidence(List.of(
                ToolEvidence.builder()
                    .source("FOLLOW_UP_TASK")
                    .summary("已发送健康报告")
                    .payload(Map.of("customerId", customerId))
                    .build()
            ))
            .build();
    }

    private Object draftWeightManagementPlan(Map<String, Object> input) {
        // 高风险操作，不会被直接调用
        // 会被 ActionExecutor 拦截并创建待确认任务
        log.warn("draftWeightManagementPlan 不应该被直接调用");
        return ToolExecutionOutput.builder()
            .data(Map.of("blocked", true))
            .summary("高风险工具不允许直接执行")
            .build();
    }

    private Object recommendMedicalReview(Map<String, Object> input) {
        // 高风险操作，不会被直接调用
        log.warn("recommendMedicalReview 不应该被直接调用");
        return ToolExecutionOutput.builder()
            .data(Map.of("blocked", true))
            .summary("高风险工具不允许直接执行")
            .build();
    }

    private Object triageRedFlags(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        Integer limit = (Integer) input.getOrDefault("limit", 20);
        List<ChatMessage> messages = patientService.getChatHistory(customerId, limit);
        String conversation = combineChatMessages(messages);

        List<String> urgentKeywords = findKeywords(conversation, List.of(
            "胸痛", "呼吸困难", "胸闷", "心悸", "晕厥", "昏迷", "抽搐", "便血", "呕血"));
        List<String> sameDayKeywords = findKeywords(conversation, List.of(
            "发热", "持续头晕", "明显加重", "无法进食", "剧烈疼痛", "严重乏力"));
        List<String> cautionKeywords = findKeywords(conversation, List.of(
            "恶心", "呕吐", "腹泻", "睡眠差", "焦虑", "抑郁", "失眠", "食欲差"));

        String triageLevel;
        String recommendedWindow;
        if (!urgentKeywords.isEmpty()) {
            triageLevel = "EMERGENCY";
            recommendedWindow = "立即线下就医或急诊";
        } else if (!sameDayKeywords.isEmpty()) {
            triageLevel = "HIGH";
            recommendedWindow = "24小时内联系医生";
        } else if (!cautionKeywords.isEmpty()) {
            triageLevel = "MEDIUM";
            recommendedWindow = "48-72小时内随访";
        } else {
            triageLevel = "LOW";
            recommendedWindow = "常规随访";
        }

        List<String> redFlags = new ArrayList<>();
        redFlags.addAll(urgentKeywords);
        redFlags.addAll(sameDayKeywords);
        redFlags.addAll(cautionKeywords);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("triageLevel", triageLevel);
        data.put("recommendedWindow", recommendedWindow);
        data.put("requiresImmediateCare", "EMERGENCY".equals(triageLevel));
        data.put("redFlags", redFlags);
        data.put("evidence", buildEvidenceLines(messages, redFlags));
        data.put("evidenceSources", List.of("CHAT_HISTORY"));
        return toolOutput("red_flag_triage", data, "已完成红旗分诊", "CHAT_HISTORY");
    }

    private Object analyzeMedicationAdherence(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        Integer chatLimit = (Integer) input.getOrDefault("chatLimit", 20);
        Integer prescriptionLimit = (Integer) input.getOrDefault("prescriptionLimit", 10);
        List<ChatMessage> messages = patientService.getChatHistory(customerId, chatLimit);
        List<PrescriptionRecord> prescriptions = patientService.getPrescriptionHistory(customerId, prescriptionLimit);
        String conversation = combineChatMessages(messages);

        List<String> missedDoseSignals = findKeywords(conversation, List.of("漏服", "忘记吃药", "没吃药", "断药"));
        List<String> stopSignals = findKeywords(conversation, List.of("停药", "自行停药", "不想吃药"));
        List<String> adjustSignals = findKeywords(conversation, List.of("加量", "减量", "自己调药", "换药"));
        String adherenceLevel = !stopSignals.isEmpty() || !adjustSignals.isEmpty() ? "HIGH_RISK"
                : !missedDoseSignals.isEmpty() ? "MEDIUM_RISK" : "STABLE";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("adherenceLevel", adherenceLevel);
        data.put("activePrescriptionCount", prescriptions.size());
        data.put("missedDoseSignals", missedDoseSignals);
        data.put("selfAdjustmentSignals", mergeLists(stopSignals, adjustSignals));
        data.put("recommendedActions", medicationAdherenceActions(adherenceLevel));
        data.put("evidence", buildEvidenceLines(messages, mergeLists(missedDoseSignals, stopSignals, adjustSignals)));
        data.put("evidenceSources", List.of("CHAT_HISTORY", "PRESCRIPTION_HISTORY"));
        return toolOutput("medication_adherence_analysis", data, "已完成用药依从性分析", "CHAT_HISTORY");
    }

    private Object summarizeSymptomTimeline(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        Integer limit = (Integer) input.getOrDefault("limit", 20);
        List<ChatMessage> messages = patientService.getChatHistory(customerId, limit);
        List<Map<String, Object>> symptoms = detectSymptoms(messages);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", symptoms.isEmpty() ? "近期聊天中未抽取到明确症状时间线" : "已抽取到近期症状时间线");
        data.put("timeline", symptoms);
        data.put("latestSymptomDate", latestChatDate(messages));
        data.put("evidenceSources", List.of("CHAT_HISTORY"));
        return toolOutput("symptom_timeline", data, "已完成症状时间线总结", "CHAT_HISTORY");
    }

    private Object assessFollowUpUrgency(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        Integer limit = (Integer) input.getOrDefault("limit", 20);
        List<ChatMessage> messages = patientService.getChatHistory(customerId, limit);
        String conversation = combineChatMessages(messages);

        boolean emergency = !findKeywords(conversation, List.of("胸痛", "呼吸困难", "晕厥", "便血", "呕血")).isEmpty();
        boolean severe = !findKeywords(conversation, List.of("明显加重", "持续头晕", "剧烈疼痛", "停药")).isEmpty();
        boolean moderate = !findKeywords(conversation, List.of("失眠", "焦虑", "食欲差", "恶心")).isEmpty();

        Map<String, Object> data = new LinkedHashMap<>();
        if (emergency) {
            data.put("urgencyLevel", "IMMEDIATE");
            data.put("recommendedWindow", "立即处理");
            data.put("reason", "近期聊天中出现急症红旗症状");
        } else if (severe) {
            data.put("urgencyLevel", "WITHIN_24H");
            data.put("recommendedWindow", "24小时内");
            data.put("reason", "近期聊天中出现恶化或用药中断信号");
        } else if (moderate) {
            data.put("urgencyLevel", "WITHIN_3_DAYS");
            data.put("recommendedWindow", "3天内");
            data.put("reason", "近期聊天中有持续不适或情绪睡眠问题");
        } else {
            data.put("urgencyLevel", "ROUTINE");
            data.put("recommendedWindow", "常规随访");
            data.put("reason", "当前未见高危或明显恶化信号");
        }
        data.put("evidence", summarizeRecentMessages(messages, 3));
        data.put("evidenceSources", List.of("CHAT_HISTORY"));
        return toolOutput("follow_up_urgency_assessment", data, "已完成随访紧急度评估", "CHAT_HISTORY");
    }

    private Object generatePatientEducationPoints(Map<String, Object> input) {
        String topic = (String) input.get("topic");
        String riskLevel = (String) input.getOrDefault("riskLevel", "LOW");
        List<HealthKnowledge> knowledgeList = healthKnowledgeService.getByTopic(topic, 3);
        if (knowledgeList.isEmpty()) {
            knowledgeList = healthKnowledgeService.searchByKeyword(topic, 3);
        }

        List<String> points = new ArrayList<>();
        for (HealthKnowledge knowledge : knowledgeList) {
            points.add(abbreviate(knowledge.getContent(), 80));
        }
        if (points.isEmpty()) {
            points.add("继续记录症状变化和日常监测数据，如有明显加重及时联系医生。");
        }
        if ("HIGH".equalsIgnoreCase(riskLevel) || "EMERGENCY".equalsIgnoreCase(riskLevel)) {
            points.add("如出现明显加重或急症信号，应优先线下就医，不要仅依赖线上观察。");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topic", topic);
        data.put("riskLevel", riskLevel);
        data.put("educationPoints", points.stream().limit(5).toList());
        data.put("evidenceSources", List.of("HEALTH_KNOWLEDGE"));
        data.put("evidence", knowledgeList.stream()
                .limit(3)
                .map(k -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("title", k.getTitle());
                    row.put("source", k.getSource() != null ? k.getSource() : "");
                    row.put("trustLevel", k.getTrustLevel() != null ? k.getTrustLevel() : "");
                    row.put("hitReason", k.getHitReason() != null ? k.getHitReason() : "");
                    row.put("score", k.getScore());
                    return row;
                })
                .toList());
        return toolOutput("patient_education_points", data, "已生成患者教育要点", "HEALTH_KNOWLEDGE");
    }

    private Object checkSelfMonitoringCompleteness(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        Integer recentDays = (Integer) input.getOrDefault("recentDays", 14);
        PatientProfile profile = patientService.getProfile(customerId);
        List<WeightRecord> weightRecords = patientService.getWeightHistory(customerId, recentDays);
        List<ChatMessage> messages = patientService.getChatHistory(customerId, 10);

        List<String> present = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (profile != null) {
            present.add("patient_profile");
        } else {
            missing.add("patient_profile");
        }
        if (!weightRecords.isEmpty()) {
            present.add("weight_history");
        } else {
            missing.add("weight_history");
        }
        boolean hasSymptomNarrative = messages.stream().map(ChatMessage::getMessage).anyMatch(this::containsSymptomKeyword);
        if (hasSymptomNarrative) {
            present.add("symptom_notes");
        } else {
            missing.add("symptom_notes");
        }
        boolean hasRecentConversation = !messages.isEmpty();
        if (hasRecentConversation) {
            present.add("chat_follow_up");
        } else {
            missing.add("chat_follow_up");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recentDays", recentDays);
        data.put("presentDataTypes", present);
        data.put("missingDataTypes", missing);
        data.put("isComplete", missing.isEmpty());
        data.put("recommendedRequests", dataRequestsForMissing(missing));
        data.put("evidenceSources", List.of("PATIENT_PROFILE", "WEIGHT_HISTORY", "CHAT_HISTORY"));
        return toolOutput("self_monitoring_completeness", data, "已完成自我监测完整性检查", "PATIENT_PROFILE");
    }

    private Object extractStructuredSymptoms(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        Integer limit = (Integer) input.getOrDefault("limit", 20);
        List<Map<String, Object>> symptoms = detectSymptoms(patientService.getChatHistory(customerId, limit));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("symptoms", symptoms);
        data.put("symptomCount", symptoms.size());
        data.put("evidenceSources", List.of("CHAT_HISTORY"));
        return toolOutput("structured_symptoms", data, "已完成症状结构化抽取", "CHAT_HISTORY");
    }

    private Object screenMentalHealthRisk(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        Integer limit = (Integer) input.getOrDefault("limit", 20);
        List<ChatMessage> messages = patientService.getChatHistory(customerId, limit);
        String conversation = combineChatMessages(messages);

        List<String> crisisKeywords = findKeywords(conversation, List.of("轻生", "自杀", "不想活", "结束自己"));
        List<String> highRiskKeywords = findKeywords(conversation, List.of("抑郁", "绝望", "崩溃"));
        List<String> mediumRiskKeywords = findKeywords(conversation, List.of("焦虑", "失眠", "心烦", "压力大"));

        String riskLevel = !crisisKeywords.isEmpty() ? "CRISIS"
                : !highRiskKeywords.isEmpty() ? "HIGH"
                : !mediumRiskKeywords.isEmpty() ? "MEDIUM" : "LOW";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("riskLevel", riskLevel);
        data.put("flags", mergeLists(crisisKeywords, highRiskKeywords, mediumRiskKeywords));
        data.put("recommendedActions", mentalHealthActions(riskLevel));
        data.put("evidence", buildEvidenceLines(messages,
                mergeLists(crisisKeywords, highRiskKeywords, mediumRiskKeywords)));
        data.put("evidenceSources", List.of("CHAT_HISTORY"));
        return toolOutput("mental_health_risk_screen", data, "已完成心理风险筛查", "CHAT_HISTORY");
    }

    private Object recommendFollowUpDataRequests(Map<String, Object> input) {
        String customerId = (String) input.get("customerId");
        String taskType = (String) input.getOrDefault("taskType", "");

        ToolExecutionOutput completeness = (ToolExecutionOutput) checkSelfMonitoringCompleteness(Map.of(
                "customerId", customerId,
                "recentDays", 14));
        @SuppressWarnings("unchecked")
        Map<String, Object> completenessData = completeness.getData() instanceof Map<?, ?>
                ? (Map<String, Object>) completeness.getData()
                : Map.of();
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) completenessData.getOrDefault("missingDataTypes", List.of());

        List<Map<String, Object>> requests = new ArrayList<>();
        for (String item : missing) {
            requests.add(buildDataRequest(item, taskType));
        }
        if (requests.isEmpty()) {
            requests.add(Map.of(
                "field", "recent_symptom_update",
                "question", "请继续保持近3天的症状与执行情况记录，后续随访可直接复用。",
                "reason", "当前关键数据已较完整"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskType", taskType);
        data.put("requests", requests);
        data.put("evidenceSources", List.of("PATIENT_PROFILE", "WEIGHT_HISTORY", "CHAT_HISTORY"));
        return toolOutput("follow_up_data_requests", data, "已生成下一轮随访数据补充建议", "PATIENT_PROFILE");
    }

    private ToolExecutionOutput toolOutput(String slotName, Object data, String summary, String source) {
        return ToolExecutionOutput.builder()
            .data(data)
            .summary(summary)
            .stateUpdates(Map.of(slotName, data))
            .evidence(List.of(
                ToolEvidence.builder()
                    .source(source)
                    .summary(summary)
                    .payload(data)
                    .build()
            ))
            .build();
    }

    private String combineChatMessages(List<ChatMessage> messages) {
        return messages.stream()
            .sorted(Comparator.comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(ChatMessage::getMessage)
            .filter(Objects::nonNull)
            .collect(Collectors.joining("；"));
    }

    private List<String> findKeywords(String text, List<String> keywords) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return keywords.stream()
            .filter(text::contains)
            .distinct()
            .toList();
    }

    @SafeVarargs
    private final List<String> mergeLists(List<String>... lists) {
        return Arrays.stream(lists)
            .filter(Objects::nonNull)
            .flatMap(Collection::stream)
            .distinct()
            .toList();
    }

    private List<String> medicationAdherenceActions(String adherenceLevel) {
        return switch (adherenceLevel) {
            case "HIGH_RISK" -> List.of("尽快由医生确认当前用药执行情况", "不要自行停药或调药");
            case "MEDIUM_RISK" -> List.of("补充近1周漏服频次", "确认是否需要提醒或简化用药计划");
            default -> List.of("继续保持当前记录", "下次随访继续核对用药执行情况");
        };
    }

    private String latestChatDate(List<ChatMessage> messages) {
        return messages.stream()
            .map(ChatMessage::getCreatedAt)
            .filter(Objects::nonNull)
            .max(LocalDateTime::compareTo)
            .map(LocalDateTime::toLocalDate)
            .map(LocalDate::toString)
            .orElse("");
    }

    private List<Map<String, Object>> detectSymptoms(List<ChatMessage> messages) {
        List<Map<String, Object>> symptoms = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message.getMessage() == null || message.getMessage().isBlank()) {
                continue;
            }
            String text = message.getMessage();
            for (String symptom : List.of("胸痛", "呼吸困难", "头晕", "恶心", "呕吐", "腹泻", "失眠", "焦虑", "抑郁", "食欲差")) {
                if (text.contains(symptom)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("symptom", symptom);
                    row.put("onset", inferOnset(text, message.getCreatedAt()));
                    row.put("trend", inferTrend(text));
                    row.put("severity", inferSeverity(text));
                    row.put("frequency", inferFrequency(text));
                    row.put("trigger", inferTrigger(text));
                    row.put("relievingFactor", inferRelievingFactor(text));
                    row.put("evidence", abbreviate(text, 60));
                    symptoms.add(row);
                }
            }
        }
        return symptoms.stream().distinct().toList();
    }

    private String inferOnset(String text, LocalDateTime createdAt) {
        if (text.contains("今天")) {
            return "今天";
        }
        if (text.contains("昨")) {
            return "昨天";
        }
        if (text.contains("近几天") || text.contains("这几天")) {
            return "近几天";
        }
        if (text.contains("一周")) {
            return "近一周";
        }
        return createdAt != null ? createdAt.toLocalDate().toString() : "时间未明确";
    }

    private String inferTrend(String text) {
        if (text.contains("加重") || text.contains("更严重")) {
            return "加重";
        }
        if (text.contains("缓解") || text.contains("好转")) {
            return "缓解";
        }
        return "波动/未明确";
    }

    private String inferSeverity(String text) {
        if (text.contains("剧烈") || text.contains("严重") || text.contains("明显")) {
            return "重";
        }
        if (text.contains("轻微") || text.contains("一点")) {
            return "轻";
        }
        return "中等/未明确";
    }

    private String inferFrequency(String text) {
        if (text.contains("一直") || text.contains("持续")) {
            return "持续";
        }
        if (text.contains("偶尔")) {
            return "偶发";
        }
        if (text.contains("每天")) {
            return "每日";
        }
        return "未明确";
    }

    private String inferTrigger(String text) {
        for (String keyword : List.of("进食后", "运动后", "熬夜后", "停药后", "加班后", "情绪波动后")) {
            if (text.contains(keyword)) {
                return keyword;
            }
        }
        return "未明确";
    }

    private String inferRelievingFactor(String text) {
        for (String keyword : List.of("休息后缓解", "喝水后缓解", "进食后缓解", "服药后缓解")) {
            if (text.contains(keyword)) {
                return keyword;
            }
        }
        return "未明确";
    }

    private List<String> mentalHealthActions(String riskLevel) {
        return switch (riskLevel) {
            case "CRISIS" -> List.of("立即转人工医生处理", "必要时建议患者尽快联系紧急支持资源");
            case "HIGH" -> List.of("尽快由医生进一步评估心理状态", "补充睡眠、食欲和情绪持续时间");
            case "MEDIUM" -> List.of("下一轮随访继续跟进情绪和睡眠变化", "补充压力来源和持续时间");
            default -> List.of("当前未见明显心理高风险信号，继续常规观察");
        };
    }

    private List<String> buildEvidenceLines(List<ChatMessage> messages, List<String> keywords) {
        if (messages == null || messages.isEmpty()) {
            return List.of("近期无聊天证据");
        }
        if (keywords == null || keywords.isEmpty()) {
            return summarizeRecentMessages(messages, 2);
        }
        return messages.stream()
            .map(ChatMessage::getMessage)
            .filter(Objects::nonNull)
            .filter(message -> keywords.stream().anyMatch(message::contains))
            .map(message -> abbreviate(message, 80))
            .distinct()
            .limit(3)
            .toList();
    }

    private List<String> summarizeRecentMessages(List<ChatMessage> messages, int limit) {
        return messages.stream()
            .sorted(Comparator.comparing(ChatMessage::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(Math.max(1, limit))
            .map(ChatMessage::getMessage)
            .filter(Objects::nonNull)
            .map(message -> abbreviate(message, 80))
            .toList();
    }

    private boolean containsSymptomKeyword(String text) {
        return !findKeywords(text, List.of(
                "胸痛", "呼吸困难", "头晕", "恶心", "呕吐", "腹泻", "失眠", "焦虑", "抑郁", "食欲差")).isEmpty();
    }

    private List<String> dataRequestsForMissing(List<String> missing) {
        return missing.stream()
            .map(item -> switch (item) {
                case "patient_profile" -> "补充当前身高、体重目标和既往慢病信息";
                case "weight_history" -> "补充近7到14天体重记录";
                case "symptom_notes" -> "补充症状出现时间、持续时长和变化趋势";
                case "chat_follow_up" -> "补充最近执行情况、饮食运动和主观不适";
                default -> "补充相关随访数据";
            })
            .toList();
    }

    private Map<String, Object> buildDataRequest(String missingField, String taskType) {
        return switch (missingField) {
            case "patient_profile" -> Map.of(
                    "field", "patient_profile",
                    "question", "请补充目前的基础档案信息，包括身高、当前体重、目标体重和既往慢病。",
                    "reason", "缺少基础档案，难以判断风险和随访优先级");
            case "weight_history" -> Map.of(
                    "field", "weight_history",
                    "question", "请补充近7到14天的体重记录，最好带日期。",
                    "reason", "缺少近期体重趋势，无法判断是否恶化或进入平台期");
            case "symptom_notes" -> Map.of(
                    "field", "symptom_notes",
                    "question", "请补充症状从什么时候开始、持续多久、有没有明显加重或缓解。",
                    "reason", "缺少症状时间线，难以判断风险");
            case "chat_follow_up" -> Map.of(
                    "field", "chat_follow_up",
                    "question", "请补充最近几天的饮食、运动、睡眠和主观不适变化。",
                    "reason", "缺少随访执行情况，不利于生成下一步建议");
            default -> Map.of(
                    "field", missingField,
                    "question", "请补充当前缺失的关键信息。",
                    "reason", "当前任务 " + taskType + " 仍存在信息缺口");
        };
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(16, maxLength)) + "...";
    }
}
