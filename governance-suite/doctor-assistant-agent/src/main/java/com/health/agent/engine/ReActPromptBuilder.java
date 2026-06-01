package com.health.agent.engine;

import com.alibaba.fastjson2.JSON;
import com.health.agent.engine.subagent.AgentSubAgent;
import com.health.agent.model.Message;
import com.health.agent.model.Step;
import com.health.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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
}
