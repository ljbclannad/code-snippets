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
import com.health.agent.tool.Tool;
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
public class ReActPromptBuilder {

    /**
     * 构建 ReAct 循环的 System Prompt
     */
    public String buildSystemPrompt(List<Tool> availableTools) {
        return String.format("""
            你是一位资深健康管理医生的AI助手Agent。你的任务是通过多步推理和工具调用，帮助医生分析患者情况。

            ## 输出协议

            你只能输出一个合法 JSON 对象，不能输出 Markdown、代码块说明、前后缀解释。
            协议版本固定为 `v1`。

            当你还需要继续调用工具时，输出：
            {
              "protocolVersion": "v1",
              "thinking": "你对当前情况的分析和下一步计划",
              "finished": false,
              "action": {
                "toolName": "工具名",
                "input": {
                  "字段": "值"
                },
                "reason": "执行该工具的原因"
              }
            }

            当你已经可以给出最终结论时，输出：
            {
              "protocolVersion": "v1",
              "thinking": "最终分析",
              "finished": true,
              "finalAnswer": {
                "summary": "给医生的简要结论",
                "keyFindings": ["关键发现1", "关键发现2"],
                "riskLevel": "LOW | MEDIUM | HIGH",
                "recommendedActions": ["建议动作1", "建议动作2"],
                "needsAttention": true,
                "uncertainties": ["待补充信息1"],
                "supportingEvidence": ["证据1", "证据2"],
                "evidenceSources": ["WEIGHT_HISTORY", "CHAT_HISTORY"],
                "inferences": ["归纳判断1"]
              }
            }

            约束：
            1. 每次只能调用一个工具
            2. 只能使用下方列出的工具名和字段
            3. finished=true 时禁止输出 action
            4. finished=false 时禁止输出 finalAnswer
            5. 不得伪造 Observation，不得跳过工具直接声称已获取事实
            6. 涉及诊断或处方建议时，必须通过高风险工具触发审批链路

            ## 可用工具

            %s

            ## 重要原则

            1. **循序渐进**：先查询基础信息，再根据发现决定下一步
            2. **有的放矢**：只调用真正需要的工具，不要盲目查询所有数据
            3. **关注异常**：重点关注风险信号（体重异常、负面情绪、长期未打卡）
            4. **停滞期优先识别**：当目标与减重停滞、平台期、近期不下降相关时，优先考虑调用 `detect_weight_plateau`
            5. **依从性优先分析**：当目标涉及饮食执行差、运动中断、打卡不足、计划难以坚持时，优先考虑调用 `analyze_adherence_signals`
            6. **医疗谨慎**：涉及诊断和处方时，必须基于充分证据
            7. **结构化输出**：最终结论必须拆成摘要、关键发现、风险等级、建议动作、是否重点关注、不确定项
            8. **解释推理**：thinking 中保留必要推理，但不要输出无关废话

            ## 示例：调用工具

            {
              "protocolVersion": "v1",
              "thinking": "我需要先获取患者的基础资料。",
              "finished": false,
              "action": {
                "toolName": "get_patient_profile",
                "input": {
                  "customerId": "1001"
                },
                "reason": "先获取患者档案"
              }
            }

            ## 示例：结束任务

            {
              "protocolVersion": "v1",
              "thinking": "已经收集到足够的患者信息。",
              "finished": true,
              "finalAnswer": {
                "summary": "这里输出给医生的简要分析结论。",
                "keyFindings": ["体重整体下降", "近期有情绪波动"],
                "riskLevel": "MEDIUM",
                "recommendedActions": ["继续记录饮食", "增加一次随访"],
                "needsAttention": true,
                "uncertainties": ["缺少完整运动记录"],
                "supportingEvidence": ["近30天体重整体下降", "聊天中提到近期焦虑"],
                "evidenceSources": ["WEIGHT_HISTORY", "CHAT_HISTORY"],
                "inferences": ["情绪因素可能影响减重执行"]
              }
            }

            现在开始执行任务。
            """, formatToolDescriptions(availableTools));
    }

    public String buildSubAgentSystemPrompt(AgentSubAgent subAgent, List<Tool> availableTools) {
        return buildSystemPrompt(availableTools) + """

            ## 当前子代理角色

            你当前扮演：%s
            角色职责：%s

            你仍然必须遵守上方的 JSON 协议与安全约束，但应优先按照当前角色职责行动。
            """.formatted(subAgent.name(), subAgent.responsibility());
    }

    /**
     * 格式化工具描述
     */
    private String formatToolDescriptions(List<Tool> tools) {
        return tools.stream()
            .map(tool -> String.format("""
                ### %s
                描述: %s
                风险等级: %s
                参数定义: %s
                """,
                tool.getName(),
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
