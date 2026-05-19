package com.health.agent.engine;

import com.alibaba.fastjson2.JSON;
import com.health.agent.model.ActionDecision;
import com.health.agent.model.Message;
import com.health.agent.model.Step;
import com.health.agent.model.StepType;
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

            ## 工作模式：ReAct (Reasoning + Acting)

            你需要按照以下格式进行思考和行动：

            Thought: [你对当前情况的分析和下一步计划]
            Action: [你决定调用的工具]
            Action Input: [工具的输入参数，JSON格式]

            然后你会收到：
            Observation: [工具执行的结果]

            你可以重复多次 Thought -> Action -> Observation 循环，直到你有足够信息完成任务。

            当你准备好给出最终答案时，使用：
            Thought: [最终分析]
            Final Answer: [你的完整回答]

            ## 可用工具

            %s

            ## 重要原则

            1. **循序渐进**：先查询基础信息，再根据发现决定下一步
            2. **有的放矢**：只调用真正需要的工具，不要盲目查询所有数据
            3. **关注异常**：重点关注风险信号（体重异常、负面情绪、长期未打卡）
            4. **医疗谨慎**：涉及诊断和处方时，必须基于充分证据
            5. **解释推理**：在Thought中清晰说明你的推理过程

            ## 示例

            Goal: 分析患者张三的健康状况

            Thought: 我需要先了解患者的基本信息，包括年龄、性别、目标和当前健康状况。
            Action: get_patient_profile
            Action Input: {"customerId": "张三"}

            Observation: {"age": 35, "gender": "女", "targetWeight": 55.0, "currentWeight": 62.0, "chronicDiseases": ["高血压"], "bmi": 22.5}

            Thought: 患者35岁女性，目标减重7kg，有高血压病史。BMI 22.5属于正常范围，但有减重目标。我需要查看体重变化趋势来评估进展。
            Action: get_weight_history
            Action Input: {"customerId": "张三", "days": 30}

            Observation: [体重数据显示最近30天下降了3kg，但最近3天反弹了1kg]

            Thought: 体重整体趋势良好，但最近出现反弹。这可能是正常波动，也可能有其他原因。我应该查看聊天记录，了解患者最近的状态和情绪。
            Action: get_chat_history
            Action Input: {"customerId": "张三", "limit": 10}

            Observation: [聊天记录显示患者提到"有点焦虑"、"是不是方法不对"]

            Thought: 患者出现焦虑情绪，这可能影响了体重管理。结合高血压病史，我现在有足够信息生成综合分析了。
            Final Answer: [完整的患者分析报告]

            现在开始执行任务。
            """, formatToolDescriptions(availableTools));
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
                参数: %s
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

            请开始你的分析。记住使用 Thought -> Action -> Observation 的格式。
            """, goal, customerId);
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
                    currentAssistant.append("Thought: ").append(step.getContent()).append("\n");
                    break;

                case ACTION:
                    currentAssistant.append("Action: ").append(step.getAction().getToolName()).append("\n");
                    currentAssistant.append("Action Input: ").append(
                        JSON.toJSONString(step.getAction().getInput())
                    ).append("\n");

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
