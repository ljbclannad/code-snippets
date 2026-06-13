package com.health.agent.engine;

import com.alibaba.fastjson2.JSON;
import com.health.agent.exception.AgentException;
import com.health.agent.model.ActionDecision;
import com.health.agent.model.StructuredFinalAnswer;
import com.health.agent.model.ThoughtResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ReAct 响应解析器
 */
@Component
public class ReActResponseParser {

    /**
     * 解析严格 JSON 协议的 ReAct 响应。
     */
    public ThoughtResult parse(String response) {
        AgentResponse payload = parsePayload(response);
        validatePayload(payload);

        String thinking = payload.thinking != null ? payload.thinking.trim() : "";
        if (payload.finished) {
            return ThoughtResult.finished(thinking, payload.finalAnswer);
        }

        ActionDecision action = ActionDecision.builder()
            .toolName(payload.action.toolName.trim())
            .input(payload.action.input)
            .reason(extractReason(payload.action.reason, thinking))
            .build();

        return ThoughtResult.continueWith(thinking, action);
    }

    private AgentResponse parsePayload(String response) {
        try {
            return JSON.parseObject(stripCodeFence(response), AgentResponse.class);
        } catch (Exception e) {
            throw new AgentException("协议解析失败：响应不是合法 JSON");
        }
    }

    private void validatePayload(AgentResponse payload) {
        if (payload == null) {
            throw new AgentException("协议解析失败：响应为空");
        }
        if (!"v1".equals(payload.protocolVersion)) {
            throw new AgentException("协议校验失败：protocolVersion 必须为 v1");
        }
        if (payload.thinking == null || payload.thinking.isBlank()) {
            throw new AgentException("协议校验失败：thinking 不能为空");
        }
        if (payload.finished) {
            if (payload.action != null) {
                throw new AgentException("协议校验失败：finished=true 时不能包含 action");
            }
            if (payload.finalAnswer == null || payload.finalAnswer.getSummary() == null || payload.finalAnswer.getSummary().isBlank()) {
                throw new AgentException("协议校验失败：finished=true 时 finalAnswer 不能为空");
            }
            return;
        }
        if (payload.finalAnswer != null) {
            throw new AgentException("协议校验失败：finished=false 时不能包含 finalAnswer");
        }
        if (payload.action == null) {
            throw new AgentException("协议校验失败：finished=false 时必须包含 action");
        }
        if (payload.action.toolName == null || payload.action.toolName.isBlank()) {
            throw new AgentException("协议校验失败：action.toolName 不能为空");
        }
        if (payload.action.input == null) {
            throw new AgentException("协议校验失败：action.input 不能为空");
        }
    }

    private String stripCodeFence(String response) {
        String trimmed = response == null ? "" : response.trim();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLineEnd > 0 && lastFence > firstLineEnd) {
                return trimmed.substring(firstLineEnd + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private String extractReason(String explicitReason, String thinking) {
        if (explicitReason != null && !explicitReason.isBlank()) {
            return explicitReason;
        }
        return thinking.length() <= 100 ? thinking : thinking.substring(0, 100) + "...";
    }

    private static class AgentResponse {
        public String protocolVersion;
        public String thinking;
        public boolean finished;
        public StructuredFinalAnswer finalAnswer;
        public ActionPayload action;
    }

    private static class ActionPayload {
        public String toolName;
        public Map<String, Object> input;
        public String reason;
    }
}
