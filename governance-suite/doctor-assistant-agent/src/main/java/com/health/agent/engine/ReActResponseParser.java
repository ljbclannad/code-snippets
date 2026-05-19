package com.health.agent.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.health.agent.exception.AgentException;
import com.health.agent.model.ActionDecision;
import com.health.agent.model.ThoughtResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ReAct 响应解析器
 */
@Component
public class ReActResponseParser {

    /**
     * 解析 Claude 的 ReAct 响应
     */
    public ThoughtResult parse(String response) {
        // 提取 Thought
        String thought = extractSection(response, "Thought:");

        // 检查是否是最终答案
        if (response.contains("Final Answer:")) {
            String finalAnswer = extractSection(response, "Final Answer:");
            return ThoughtResult.finished(thought, finalAnswer);
        }

        // 提取 Action
        String actionName = extractSection(response, "Action:");
        String actionInputStr = extractSection(response, "Action Input:");

        if (actionName == null || actionInputStr == null) {
            throw new AgentException("无法解析Action: " + response);
        }

        // 解析 Action Input (JSON)
        Map<String, Object> actionInput;
        try {
            actionInput = JSON.parseObject(actionInputStr,
                new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new AgentException("Action Input不是有效的JSON: " + actionInputStr);
        }

        // 提取推理原因
        String reason = extractReason(thought);

        ActionDecision action = ActionDecision.builder()
            .toolName(actionName.trim())
            .input(actionInput)
            .reason(reason)
            .build();

        return ThoughtResult.continueWith(thought, action);
    }

    /**
     * 提取文本中的特定部分
     */
    private String extractSection(String text, String marker) {
        int start = text.indexOf(marker);
        if (start == -1) {
            return null;
        }

        start += marker.length();

        // 找到下一个标记或文本结束
        String[] nextMarkers = {"Thought:", "Action:", "Action Input:", "Observation:", "Final Answer:"};
        int end = text.length();

        for (String nextMarker : nextMarkers) {
            int pos = text.indexOf(nextMarker, start);
            if (pos != -1 && pos < end) {
                end = pos;
            }
        }

        return text.substring(start, end).trim();
    }

    /**
     * 从 Thought 中提取推理原因
     */
    private String extractReason(String thought) {
        if (thought == null) {
            return "";
        }
        if (thought.length() <= 100) {
            return thought;
        }
        return thought.substring(0, 100) + "...";
    }
}
