package com.health.agent.model;

import com.alibaba.fastjson2.JSON;
import com.lx.governance.agentplatform.model.PlatformContextVisibility;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContext {
    /**
     * doctor-assistant-agent 在一次任务执行中的显式工作内存。
     *
     * <p>它把主体信息、结构化状态槽位和证据记录统一收敛起来，
     * 供 ReAct 主代理、子代理协作、状态恢复和最终审计共同使用。</p>
     */

    /**
     * 当前会话绑定的患者ID。
     * 之所以放在上下文里，而不是只放在外层 session/request 中，
     * 是因为后续子代理、工具输出和状态恢复都需要知道“这份工作内存属于谁”，
     * 避免在跨步骤恢复和多角色协作时丢失主体信息。
     */
    private String customerId;

    /**
     * 当前会话绑定的医生ID。
     * 这个字段让上下文不仅知道“分析的是哪个患者”，也知道“服务的是哪个医生”，
     * 便于后续审批链、审计链和多角色协作在同一份上下文里保留责任边界。
     */
    private String doctorId;

    /**
     * 当前会话的原始目标。
     * 之所以保留在显式状态中，是因为子代理切换、状态恢复、Prompt 重建时，
     * 都需要拿到最初任务意图，而不必只依赖 messages 里的自然语言重放。
     */
    private String goal;

    /**
     * 显式状态槽位集合，是 AgentContext 的核心。
     *
     * 这里存放的是“Agent 当前已经掌握的结构化事实”，例如：
     * - patient_profile
     * - weight_history
     * - plateau_assessment
     * - adherence_analysis
     *
     * 为什么需要 slots：
     * 1. 让工具之间通过结构化状态传递信息，而不是只靠 Observation 文本
     * 2. 让子代理协调器能根据当前已知状态选择下一位子代理
     * 3. 让会话恢复时可以直接拿到工作内存，而不是重新解析历史文本
     * 4. 让 Prompt 可以注入状态摘要，减少模型重复读取长消息历史
     */
    @Builder.Default
    private Map<String, ContextEntry> slots = new LinkedHashMap<>();

    /**
     * 证据记录列表。
     *
     * slots 解决的是“当前知道了什么”，evidenceRecords 解决的是“为什么知道这些”。
     * 它用于保留事实来源、工具来源和摘要，支撑：
     * - 最终答案里的证据链
     * - 高风险输出审查
     * - 审计回溯
     * - 子代理之间的事实复用
     *
     * 简单说：slots 更偏工作内存，evidenceRecords 更偏可追溯性。
     */
    @Builder.Default
    private List<EvidenceRecord> evidenceRecords = new ArrayList<>();

    /**
     * 创建一份带主体信息和初始空状态的上下文。
     * 这一步相当于为一次 Agent 会话初始化工作内存。
     */
    public static AgentContext initialize(String customerId, String doctorId, String goal) {
        return AgentContext.builder()
            .customerId(customerId)
            .doctorId(doctorId)
            .goal(goal)
            .slots(new LinkedHashMap<>())
            .evidenceRecords(new ArrayList<>())
            .build();
    }

    /**
     * 创建空上下文。
     * 主要用于兼容某些旧路径或默认占位，避免在服务层传 null。
     */
    public static AgentContext empty() {
        return initialize(null, null, null);
    }

    /**
     * 写入一个状态槽位。
     *
     * key 表示槽位名，value 表示结构化事实，source 表示谁写入了这条状态。
     * 这里不直接保留“最后一次 Observation 文本”，而是保留已经沉淀后的结构化状态，
     * 目的是让后续推理和工具调用可以稳定引用。
     */
    public void putSlot(String key, Object value, String source) {
        putSlot(key, value, source, PlatformContextVisibility.AGENT_INTERNAL);
    }

    public void putSlot(String key, Object value, String source, PlatformContextVisibility visibility) {
        if (key == null || key.isBlank()) {
            return;
        }
        slots.put(key, ContextEntry.builder()
            .key(key)
            .value(value)
            .source(source)
            .visibility(visibility != null ? visibility : PlatformContextVisibility.AGENT_INTERNAL)
            .updatedAt(LocalDateTime.now())
            .build());
    }

    /**
     * 追加一条证据记录。
     *
     * 这一层不是为了替代 slots，而是为了给状态和最终结论补“来源依据”，
     * 让系统在多步执行后仍能回答：
     * - 这条事实来自哪个工具
     * - 为什么会得到这个判断
     * - 是否足以支撑高风险建议
     */
    public void addEvidence(String toolName, String source, String summary, Object payload) {
        addEvidence(toolName, source, summary, payload, PlatformContextVisibility.AGENT_INTERNAL);
    }

    public void addEvidence(
            String toolName,
            String source,
            String summary,
            Object payload,
            PlatformContextVisibility visibility) {
        evidenceRecords.add(EvidenceRecord.builder()
            .toolName(toolName)
            .source(source)
            .summary(summary)
            .payload(payload)
            .visibility(visibility != null ? visibility : PlatformContextVisibility.AGENT_INTERNAL)
            .recordedAt(LocalDateTime.now())
            .build());
    }

    /**
     * 将当前显式状态转成适合注入 Prompt 的摘要文本。
     *
     * 这里不把全部上下文原样塞回模型，而是只抽取关键 slots 的缩略表示，
     * 目的是：
     * 1. 让模型知道当前已经掌握了什么
     * 2. 控制 Prompt 体积，避免无上限增长
     * 3. 在恢复执行或切换子代理时，继续共享同一份工作内存
     */
    public String toPromptSection() {
        return toPromptSection(12, 300, 2400);
    }

    public String toPromptSection(int maxSlots, int maxCharsPerSlot, int maxTotalChars) {
        if (slots == null || slots.isEmpty()) {
            return "当前无已缓存状态。";
        }

        StringBuilder builder = new StringBuilder();
        int slotCount = 0;
        for (Map.Entry<String, ContextEntry> entry : slots.entrySet()) {
            if (slotCount >= Math.max(1, maxSlots) || builder.length() >= Math.max(256, maxTotalChars)) {
                break;
            }
            String serialized = JSON.toJSONString(entry.getValue().getValue());
            if (serialized.length() > maxCharsPerSlot) {
                serialized = serialized.substring(0, maxCharsPerSlot) + "...";
            }
            String line = "- " + entry.getKey() + ": " + serialized + '\n';
            if (builder.length() + line.length() > Math.max(256, maxTotalChars)) {
                break;
            }
            builder.append(line);
            slotCount++;
        }
        return builder.toString().trim();
    }
}
