package com.health.agent.scene.doctor.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.health.agent.tool.ToolRiskLevel;
import com.lx.governance.agentplatform.model.ToolDefinition;
import com.lx.governance.agentplatform.model.ToolIdempotencyPolicy;
import com.lx.governance.agentplatform.model.ToolRetryPolicy;
import com.lx.governance.agentplatform.model.ToolDescriptor;
import com.lx.governance.agentplatform.registry.InMemoryPlatformToolRegistry;

/**
 * 医生减重场景工具定义中心，仅维护元数据，不绑定执行器。
 */
@Component
public class DoctorToolDefinitionRegistry extends InMemoryPlatformToolRegistry {

    public DoctorToolDefinitionRegistry() {
        registerDefinitions();
    }

    private void registerDefinitions() {
        register("get_patient_profile", "查询患者基本信息（年龄、性别、目标、慢性病等）", ToolRiskLevel.LOW, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64)
        ));
        register("get_weight_history", "查询患者体重历史记录", ToolRiskLevel.LOW, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64),
                "days", Map.of("type", "integer", "default", 30, "min", 1, "max", 90)
        ));
        register("get_chat_history", "查询患者最近的聊天记录", ToolRiskLevel.LOW, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64),
                "limit", Map.of("type", "integer", "default", 20, "min", 1, "max", 50)
        ));
        register("get_prescription_history", "查询患者的处方历史", ToolRiskLevel.LOW, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64),
                "limit", Map.of("type", "integer", "default", 10, "min", 1, "max", 30)
        ));
        register("calculate_bmi", "计算BMI指数", ToolRiskLevel.LOW, Map.of(
                "weight", Map.of("type", "number", "required", true, "min", 1, "max", 500),
                "height", Map.of("type", "number", "required", true, "min", 50, "max", 250)
        ));
        register("analyze_weight_trend", "分析体重变化趋势", ToolRiskLevel.LOW, Map.of(
                "weightRecords", Map.of("type", "array", "required", true, "minItems", 1, "maxItems", 180)
        ));
        register("detect_weight_plateau", "识别是否进入减重停滞期", ToolRiskLevel.LOW, Map.of(
                "weightRecords", Map.of("type", "array", "required", true, "minItems", 2, "maxItems", 180)
        ));
        register("analyze_adherence_signals", "分析饮食和运动依从性信号", ToolRiskLevel.LOW, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64),
                "recentDays", Map.of("type", "integer", "default", 14, "min", 7, "max", 30),
                "taskLimit", Map.of("type", "integer", "default", 10, "min", 1, "max", 30)
        ));
        register("get_health_knowledge", "查询减重管理相关健康知识", ToolRiskLevel.LOW, Map.of(
                "topic", Map.of("type", "string", "required", true, "maxLength", 100)
        ));
        register("triage_red_flags", "基于近期聊天内容识别红旗症状并给出分诊级别", ToolRiskLevel.LOW, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64),
                "limit", Map.of("type", "integer", "default", 20, "min", 1, "max", 50)
        ));
        register("analyze_medication_adherence", "分析近期聊天和处方历史中的漏服、停药、自行调药风险", ToolRiskLevel.LOW, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64),
                "chatLimit", Map.of("type", "integer", "default", 20, "min", 1, "max", 50),
                "prescriptionLimit", Map.of("type", "integer", "default", 10, "min", 1, "max", 30)
        ));
        register("summarize_symptom_timeline", "从近期聊天内容中抽取症状出现时间、持续时长、变化趋势和伴随症状", ToolRiskLevel.LOW, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64),
                "limit", Map.of("type", "integer", "default", 20, "min", 1, "max", 50)
        ));
        register("assess_follow_up_urgency", "结合近期聊天风险信号评估随访紧急度和推荐时窗", ToolRiskLevel.LOW, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64),
                "limit", Map.of("type", "integer", "default", 20, "min", 1, "max", 50)
        ));
        register("generate_patient_education_points", "生成患者教育要点，强调生活方式、监测提醒和何时寻求帮助", ToolRiskLevel.LOW, Map.of(
                "topic", Map.of("type", "string", "required", true, "maxLength", 100),
                "riskLevel", Map.of("type", "string", "required", false, "maxLength", 16)
        ));
        register("check_self_monitoring_completeness", "检查近期自我监测数据是否缺关键项目，如体重、症状或聊天随访反馈", ToolRiskLevel.LOW, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64),
                "recentDays", Map.of("type", "integer", "default", 14, "min", 3, "max", 30)
        ));
        register("extract_structured_symptoms", "从近期聊天中结构化抽取症状、程度、频率、诱因和缓解因素", ToolRiskLevel.LOW, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64),
                "limit", Map.of("type", "integer", "default", 20, "min", 1, "max", 50)
        ));
        register("screen_mental_health_risk", "筛查近期聊天中的焦虑、抑郁、失眠和轻生等心理风险", ToolRiskLevel.LOW, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64),
                "limit", Map.of("type", "integer", "default", 20, "min", 1, "max", 50)
        ));
        register("recommend_follow_up_data_requests", "根据当前数据缺口给出下一轮随访需补充的关键数据字段", ToolRiskLevel.LOW, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64),
                "taskType", Map.of("type", "string", "required", false, "maxLength", 64)
        ));
        register("send_reminder", "给患者发送减重管理提醒消息", ToolRiskLevel.MEDIUM, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64),
                "message", Map.of("type", "string", "required", true, "maxLength", 500)
        ));
        register("create_follow_up_task", "创建减重随访任务", ToolRiskLevel.MEDIUM, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64),
                "taskDescription", Map.of("type", "string", "required", true, "maxLength", 500),
                "dueDate", Map.of("type", "string", "required", true, "maxLength", 32)
        ));
        register("send_health_report", "发送减重阶段报告给患者", ToolRiskLevel.MEDIUM, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64),
                "reportContent", Map.of("type", "string", "required", true, "maxLength", 4000)
        ));
        register("draft_weight_management_plan", "起草减重管理方案建议（需要医生审核确认）", ToolRiskLevel.HIGH, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64),
                "recommendations", Map.of("type", "array", "required", true, "itemType", "string", "minItems", 1, "maxItems", 20),
                "reason", Map.of("type", "string", "required", true, "maxLength", 500)
        ));
        register("recommend_medical_review", "建议转人工医学复核（需要医生审核确认）", ToolRiskLevel.HIGH, Map.of(
                "customerId", Map.of("type", "string", "required", true, "maxLength", 64),
                "reviewReason", Map.of("type", "string", "required", true, "maxLength", 500),
                "evidence", Map.of("type", "array", "required", true, "itemType", "string", "minItems", 1, "maxItems", 20)
        ));
    }

    private void register(String name, String description, ToolRiskLevel riskLevel, Map<String, Object> inputSchema) {
        super.register(ToolDefinition.builder()
                .toolName(name)
                .description(description)
                .schema(inputSchema)
                .riskLevel(riskLevel != null ? riskLevel.name() : "UNKNOWN")
                .owner("doctor-assistant-agent")
                .timeoutMs(defaultTimeoutMs(riskLevel))
                .retryPolicy(defaultRetryPolicy(riskLevel))
                .idempotencyPolicy(defaultIdempotencyPolicy(name))
                .auditEnabled(true)
                .qpsLimit(defaultQpsLimit(riskLevel))
                .circuitBreakerFailureThreshold(defaultCircuitBreakerFailureThreshold(riskLevel))
                .circuitOpenMillis(defaultCircuitOpenMillis(riskLevel))
                .build());
    }

    private long defaultTimeoutMs(ToolRiskLevel riskLevel) {
        if (riskLevel == ToolRiskLevel.HIGH) {
            return 5_000L;
        }
        if (riskLevel == ToolRiskLevel.MEDIUM) {
            return 4_000L;
        }
        return 3_000L;
    }

    private ToolRetryPolicy defaultRetryPolicy(ToolRiskLevel riskLevel) {
        if (riskLevel == ToolRiskLevel.LOW) {
            return ToolRetryPolicy.builder()
                    .maxAttempts(2)
                    .backoffMs(200L)
                    .retryOnTimeout(true)
                    .retryOnThrottled(true)
                    .build();
        }
        return ToolRetryPolicy.noRetry();
    }

    private ToolIdempotencyPolicy defaultIdempotencyPolicy(String toolName) {
        return switch (toolName) {
            case "send_reminder", "create_follow_up_task", "send_health_report",
                    "draft_weight_management_plan", "recommend_medical_review" ->
                    ToolIdempotencyPolicy.NON_IDEMPOTENT;
            default -> ToolIdempotencyPolicy.IDEMPOTENT;
        };
    }

    private int defaultQpsLimit(ToolRiskLevel riskLevel) {
        if (riskLevel == ToolRiskLevel.HIGH) {
            return 2;
        }
        if (riskLevel == ToolRiskLevel.MEDIUM) {
            return 5;
        }
        return 20;
    }

    private int defaultCircuitBreakerFailureThreshold(ToolRiskLevel riskLevel) {
        if (riskLevel == ToolRiskLevel.HIGH) {
            return 1;
        }
        if (riskLevel == ToolRiskLevel.MEDIUM) {
            return 2;
        }
        return 3;
    }

    private long defaultCircuitOpenMillis(ToolRiskLevel riskLevel) {
        if (riskLevel == ToolRiskLevel.HIGH) {
            return 60_000L;
        }
        if (riskLevel == ToolRiskLevel.MEDIUM) {
            return 30_000L;
        }
        return 10_000L;
    }
}
