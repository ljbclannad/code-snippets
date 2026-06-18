package com.health.agent.engine;

import com.alibaba.fastjson2.JSON;
import com.health.agent.dto.agent.AgentExecutionCommand;
import com.health.agent.dto.patient.MemorySnapshot;
import com.health.agent.dto.patient.TaskType;
import com.health.agent.engine.subagent.AgentSubAgent;
import com.health.agent.model.AgentContext;
import com.health.agent.model.ContextEntry;
import com.health.agent.model.Message;
import com.health.agent.model.Step;
import com.health.agent.scene.doctor.prompt.DoctorScenePromptTemplate;
import com.health.agent.tool.Tool;
import com.lx.governance.agentplatform.model.PromptRenderRequest;
import com.lx.governance.agentplatform.model.PromptTemplateType;
import com.lx.governance.agentplatform.model.ToolDescriptor;
import com.lx.governance.agentplatform.runtime.PromptRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * ReAct Prompt 构建器
 */
@Component
public class ReActPromptBuilder implements AgentPromptProvider {

    public static final String DOCTOR_SCENE = "doctor-weight-management";
    public static final String DOCTOR_AGENT = "doctor-assistant-agent";
    public static final String DEFAULT_PROMPT_VERSION = "doctor-react-prompt-v1";

    @Autowired
    private DoctorScenePromptTemplate doctorScenePromptTemplate = new DoctorScenePromptTemplate();

    @Autowired(required = false)
    private PromptRegistry promptRegistry;

    /**
     * 构建 ReAct 循环的 System Prompt
     */
    public String buildSystemPrompt(List<Tool> availableTools) {
        return doctorScenePromptTemplate.buildSystemPrompt(doctorScenePromptTemplate.fromTools(availableTools));
    }

    public String buildSubAgentSystemPrompt(AgentSubAgent subAgent, List<Tool> availableTools) {
        return doctorScenePromptTemplate.buildSubAgentSystemPrompt(
                subAgent,
                doctorScenePromptTemplate.fromTools(availableTools));
    }

    @Override
    public String buildSubAgentSystemPromptFromCatalog(AgentSubAgent subAgent, List<ToolDescriptor> availableTools) {
        if (promptRegistry != null) {
            return promptRegistry.renderActive(
                    DOCTOR_SCENE,
                    DOCTOR_AGENT,
                    PromptTemplateType.SUB_AGENT_SYSTEM,
                    PromptRenderRequest.builder()
                            .variable("subAgent", subAgent)
                            .variable("availableTools", availableTools)
                            .build())
                    .getContent();
        }
        return doctorScenePromptTemplate.buildSubAgentSystemPrompt(subAgent, availableTools);
    }

    public String buildSystemPromptFromCatalog(List<ToolDescriptor> availableTools) {
        if (promptRegistry != null) {
            return promptRegistry.renderActive(
                    DOCTOR_SCENE,
                    DOCTOR_AGENT,
                    PromptTemplateType.SYSTEM,
                    PromptRenderRequest.builder()
                            .variable("availableTools", availableTools)
                            .build())
                    .getContent();
        }
        return doctorScenePromptTemplate.buildSystemPrompt(availableTools);
    }

    private String formatToolDescriptors(List<ToolDescriptor> tools) {
        return tools.stream()
            .map(tool -> String.format("""
                ### %s
                描述: %s
                风险等级: %s
                参数定义: %s
                """,
                tool.getToolName(),
                tool.getDescription(),
                tool.getRiskLevel(),
                JSON.toJSONString(tool.getInputSchema())
            ))
            .collect(Collectors.joining("\n"));
    }

    /**
     * 构建用户请求 Prompt
     */
    public String buildUserPrompt(String goal, String customerId) {
        return String.format("""
            Goal: %s
            Patient ID: %s

            请开始分析，并严格遵守 JSON 协议。
            """, goal, customerId);
    }

    /**
     * 为患者任务升级链路构建首轮 user prompt。
     *
     * <p>相比只给出 goal/customerId，这里会显式注入患者消息、任务类型、
     * 上游触发原因、画像摘要和记忆快照，帮助真实 Agent 首轮就利用上游资产，
     * 减少无意义重复采集。</p>
     */
    public String buildUserPrompt(AgentExecutionCommand command) {
        if (command == null) {
            return buildUserPrompt(null, null);
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Goal: ").append(defaultText(command.getGoal())).append('\n');
        builder.append("Patient ID: ").append(defaultText(command.getCustomerId())).append('\n');
        builder.append("Doctor ID: ").append(defaultText(command.getDoctorId())).append('\n');

        if (command.getTaskType() != null) {
            builder.append("Task Type: ").append(command.getTaskType().name()).append('\n');
        }
        if (command.getConversationId() != null && !command.getConversationId().isBlank()) {
            builder.append("Conversation ID: ").append(command.getConversationId()).append('\n');
        }
        if (command.getCorrelationId() != null && !command.getCorrelationId().isBlank()) {
            builder.append("Correlation ID: ").append(command.getCorrelationId()).append('\n');
        }

        appendSection(builder, "Current Patient Message", command.getPatientMessage());
        appendSection(builder, "Trigger Decision Summary", extractTriggerSummary(command.getTriggerDecision()));
        appendSection(builder, "Trigger Decision Reasons", extractTriggerReasons(command.getTriggerDecision()));
        appendSection(builder, "Profile Snapshot Summary", summarizeMap(command.getProfileSnapshot(), 8));
        appendSection(builder, "Session Context Summary", summarizeMap(command.getSessionContext(), 8));

        MemorySnapshot memorySnapshot = command.getMemorySnapshot();
        if (memorySnapshot != null) {
            appendSection(builder, "Short-term Memory Snapshot",
                    summarizeList(memorySnapshot.getShortTermMemories(), 4, 120));
            appendSection(builder, "Long-term Memory Snapshot",
                    summarizeList(memorySnapshot.getLongTermMemories(), 4, 120));
        }
        appendSection(builder, "Task-specific Analysis Focus", taskTypeGuidance(command.getTaskType()));

        builder.append("""

            请优先利用以上已预加载的患者任务快照完成分析，只在缺少关键事实时再调用工具。
            请开始分析，并严格遵守 JSON 协议。
            """);
        return builder.toString().trim();
    }

    public String buildProtocolRepairPrompt(String errorMessage) {
        if (promptRegistry != null) {
            return promptRegistry.renderActive(
                    DOCTOR_SCENE,
                    DOCTOR_AGENT,
                    PromptTemplateType.PROTOCOL_REPAIR,
                    PromptRenderRequest.builder()
                            .variable("errorMessage", errorMessage)
                            .build())
                    .getContent();
        }
        return """
            你刚才的响应不符合协议，错误是：%s

            请立即重新输出，并且：
            1. 只输出一个合法 JSON 对象
            2. protocolVersion 必须为 "v1"
            3. 不要输出 Markdown 代码块
            4. 不要解释错误，不要附加多余文本
            """.formatted(errorMessage);
    }

    /**
     * 为患者任务构建分区式上下文注入片段。
     *
     * <p>这里优先把上游快照拆成“患者消息 / 路由结论 / 画像 / 记忆 / 风险”几个固定区块，
     * 避免某个超长 slot 挤掉其他高价值上下文；若当前不是患者任务，则回退到通用摘要。</p>
     */
    public String buildContextPromptSection(AgentContext context) {
        if (promptRegistry != null) {
            try {
                return promptRegistry.renderActive(
                        DOCTOR_SCENE,
                        DOCTOR_AGENT,
                        PromptTemplateType.CONTEXT_SECTION,
                        PromptRenderRequest.builder()
                                .variable("context", context)
                                .build())
                        .getContent();
            } catch (IllegalArgumentException ignore) {
                // 回退到当前实现
            }
        }
        if (context == null) {
            return "当前无已缓存状态。";
        }
        Object patientTaskRequest = slotValue(context, "patient_task_request");
        if (!(patientTaskRequest instanceof Map<?, ?>)) {
            return context.toPromptSection();
        }

        StringBuilder builder = new StringBuilder();
        appendSection(builder, "Current Patient Message", summarizeObject(slotValue(context, "patient_message"), 320));
        appendSection(builder, "Upstream Routing Decision", summarizeRoutingDecision(context));
        appendSection(builder, "Patient Profile Facts", summarizeObject(preferredSlotValue(context,
                "patient_profile", "profile_snapshot"), 420));
        appendSection(builder, "Short-term Memory", summarizeMemorySection(slotValue(context, "chat_history"), 4, 140));
        appendSection(builder, "Long-term Memory", summarizeLongTermMemory(context));
        appendSection(builder, "Current Known Risk", summarizeKnownRisk(context));
        appendSection(builder, "Working Memory Highlights", summarizeWorkingMemory(context));

        String structured = builder.toString().trim();
        return structured.isBlank() ? context.toPromptSection() : structured;
    }

    /**
     * 构建历史对话 Prompt（用于继续 ReAct 循环）
     */
    public List<Message> buildHistoryMessages(List<Step> history) {
        List<Message> messages = new ArrayList<>();

        StringBuilder currentAssistant = new StringBuilder();

        for (Step step : history) {
            switch (step.getType()) {
                case THOUGHT:
                    currentAssistant.append("{\"protocolVersion\":\"v1\",\"thinking\":")
                        .append(JSON.toJSONString(step.getContent()))
                        .append(",");
                    break;

                case ACTION:
                    currentAssistant.append("\"finished\":false,\"action\":")
                        .append(JSON.toJSONString(step.getAction()))
                        .append("}");

                    // Assistant 消息完成
                    messages.add(Message.assistant(currentAssistant.toString()));
                    currentAssistant = new StringBuilder();
                    break;

                case OBSERVATION:
                    // Observation 作为 User 消息
                    messages.add(Message.user("Observation: " + step.getContent()));
                    break;
            }
        }

        return messages;
    }

    @Override
    public String currentPromptVersion(PromptTemplateType type) {
        if (promptRegistry == null) {
            return DEFAULT_PROMPT_VERSION;
        }
        return promptRegistry.findActiveVersion(DOCTOR_SCENE, DOCTOR_AGENT, type)
                .orElse(DEFAULT_PROMPT_VERSION);
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        builder.append('\n').append(title).append(':').append('\n');
        builder.append(content).append('\n');
    }

    private String extractTriggerSummary(Map<String, Object> triggerDecision) {
        if (triggerDecision == null || triggerDecision.isEmpty()) {
            return null;
        }
        Object summary = triggerDecision.get("summary");
        if (summary != null) {
            return String.valueOf(summary);
        }
        Object reason = triggerDecision.get("reason");
        return reason != null ? String.valueOf(reason) : null;
    }

    private String extractTriggerReasons(Map<String, Object> triggerDecision) {
        if (triggerDecision == null || triggerDecision.isEmpty()) {
            return null;
        }
        Object reasons = triggerDecision.get("reasons");
        if (reasons instanceof Collection<?> collection) {
            return collection.stream().limit(5).map(String::valueOf).collect(Collectors.joining(" | "));
        }
        return null;
    }

    private String summarizeMap(Map<String, Object> source, int maxEntries) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        return source.entrySet().stream()
                .limit(Math.max(1, maxEntries))
                .map(entry -> entry.getKey() + ": " + abbreviate(String.valueOf(entry.getValue()), 80))
                .collect(Collectors.joining("\n"));
    }

    private String summarizeList(List<?> values, int maxItems, int maxItemChars) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
                .limit(Math.max(1, maxItems))
                .map(value -> "- " + abbreviate(String.valueOf(value), maxItemChars))
                .collect(Collectors.joining("\n"));
    }

    private String abbreviate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        int safeMax = Math.max(16, maxChars);
        if (text.length() <= safeMax) {
            return text;
        }
        return text.substring(0, safeMax) + "...";
    }

    private String defaultText(String text) {
        return text == null || text.isBlank() ? "N/A" : text;
    }

    private String taskTypeGuidance(TaskType taskType) {
        if (taskType == null) {
            return "优先明确当前患者任务的目标、已知事实、证据缺口和下一步建议。";
        }
        return switch (taskType) {
            case PATIENT_FOLLOW_UP_ANALYSIS -> """
                    你的首要目标是判断患者当前反馈是否提示随访阶段的趋势变化或平台期风险。
                    请优先利用已有体重、执行情况和历史记忆，总结：
                    1. 当前最重要的趋势判断
                    2. 支撑该判断的证据
                    3. 仍需补充的关键信息
                    4. 面向下一轮随访的建议动作
                    """;
            case RISK_STRATIFICATION_AND_ESCALATION -> """
                    你的首要目标是识别风险等级，并判断是否需要尽快转人工或线下就医。
                    请优先关注急症信号、恶化趋势、药物风险和证据充分性，总结：
                    1. 当前风险等级
                    2. 是否需要人工复核或升级处置
                    3. 触发风险判断的核心依据
                    4. 当前阶段最保守的下一步建议
                    """;
            case NEXT_FOLLOW_UP_ACTION_RECOMMENDATION -> """
                    你的首要目标是给出下一步随访动作建议，而不是重新泛化分析全部病情。
                    请优先输出：
                    1. 最值得先做的1到3个后续动作
                    2. 这些动作对应要补的关键信息
                    3. 是否需要医生进一步确认
                    4. 哪些建议可以先作为患者教育或观察项
                    """;
            case NONE -> "当前任务类型未明确，请先澄清目标并保持保守分析。";
        };
    }

    private Object slotValue(AgentContext context, String slotName) {
        if (context == null || context.getSlots() == null || slotName == null) {
            return null;
        }
        ContextEntry entry = context.getSlots().get(slotName);
        return entry != null ? entry.getValue() : null;
    }

    private Object preferredSlotValue(AgentContext context, String... slotNames) {
        if (slotNames == null) {
            return null;
        }
        for (String slotName : slotNames) {
            Object value = slotValue(context, slotName);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String summarizeRoutingDecision(AgentContext context) {
        List<String> parts = new ArrayList<>();
        Object taskRequest = slotValue(context, "patient_task_request");
        if (taskRequest instanceof Map<?, ?> taskMap) {
            Object taskType = taskMap.get("taskType");
            Object source = taskMap.get("source");
            if (taskType != null) {
                parts.add("taskType: " + taskType);
            }
            if (source != null) {
                parts.add("source: " + source);
            }
        }
        Object triggerDecision = slotValue(context, "trigger_decision");
        String triggerSummary = summarizeObject(triggerDecision, 260);
        if (triggerSummary != null && !triggerSummary.isBlank()) {
            parts.add(triggerSummary);
        }
        Object sessionContext = slotValue(context, "session_context");
        String sessionSummary = summarizeObject(sessionContext, 220);
        if (sessionSummary != null && !sessionSummary.isBlank()) {
            parts.add("session: " + sessionSummary);
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    private String summarizeLongTermMemory(AgentContext context) {
        Object memorySnapshot = slotValue(context, "memory_snapshot");
        if (memorySnapshot instanceof MemorySnapshot snapshot) {
            return summarizeList(snapshot.getLongTermMemories(), 3, 110);
        }
        if (memorySnapshot instanceof Map<?, ?> memoryMap) {
            Object longTerm = memoryMap.get("longTermMemories");
            if (longTerm instanceof List<?> list) {
                return summarizeList(list, 3, 110);
            }
        }
        return null;
    }

    private String summarizeKnownRisk(AgentContext context) {
        List<String> sections = new ArrayList<>();
        addSummarySection(sections, "trigger", slotValue(context, "trigger_decision"), 180);
        addSummarySection(sections, "plateau", slotValue(context, "plateau_assessment"), 180);
        addSummarySection(sections, "adherence", slotValue(context, "adherence_analysis"), 180);
        addSummarySection(sections, "trend", slotValue(context, "weight_trend_analysis"), 180);
        return sections.isEmpty() ? null : String.join("\n", sections);
    }

    private String summarizeWorkingMemory(AgentContext context) {
        List<String> highlights = new ArrayList<>();
        highlights.add(summarizeInline("recent_follow_up_signals", slotValue(context, "recent_follow_up_signals"), 180));
        highlights.add(summarizeInline("last_tool_result", slotValue(context, "last_tool_result"), 180));
        highlights.add(summarizeInline("health_knowledge", slotValue(context, "health_knowledge"), 160));
        return highlights.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }

    private void addSummarySection(List<String> sections, String label, Object value, int maxChars) {
        String summary = summarizeObject(value, maxChars);
        if (summary != null && !summary.isBlank()) {
            sections.add(label + ": " + summary);
        }
    }

    private String summarizeInline(String label, Object value, int maxChars) {
        String summary = summarizeObject(value, maxChars);
        if (summary == null || summary.isBlank()) {
            return null;
        }
        return label + ": " + summary;
    }

    private String summarizeMemorySection(Object value, int maxItems, int maxItemChars) {
        if (value instanceof List<?> list) {
            return summarizeList(list, maxItems, maxItemChars);
        }
        return summarizeObject(value, Math.max(120, maxItems * maxItemChars));
    }

    private String summarizeObject(Object value, int maxChars) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return abbreviate(text, maxChars);
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .limit(6)
                    .map(entry -> String.valueOf(entry.getKey()) + ": "
                            + abbreviate(String.valueOf(entry.getValue()), Math.max(24, maxChars / 3)))
                    .collect(Collectors.joining("\n"));
        }
        if (value instanceof List<?> list) {
            return summarizeList(list, 4, Math.max(32, maxChars / 3));
        }
        return abbreviate(String.valueOf(value), maxChars);
    }
}
