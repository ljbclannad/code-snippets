package com.health.agent.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.health.agent.model.Message;

/**
 * 构造发送给模型的历史副本，合并较早工具结果中重复的载荷。
 */
final class ReActPromptHistory {

    static final String OBSERVATION_PREFIX = "Observation: ";

    private ReActPromptHistory() {
    }

    static List<Message> forModel(List<Message> history) {
        List<Message> messages = new ArrayList<>(history);
        boolean keptLatestObservation = false;
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (!"user".equals(message.getRole()) || message.getContent() == null
                    || !message.getContent().startsWith(OBSERVATION_PREFIX)) {
                continue;
            }
            if (keptLatestObservation) {
                messages.set(index, compactObservation(message));
            }
            keptLatestObservation = true;
        }
        return messages;
    }

    private static Message compactObservation(Message message) {
        String body = message.getContent().substring(OBSERVATION_PREFIX.length());
        if (!body.startsWith("{")) {
            return message;
        }
        try {
            JSONObject output = JSON.parseObject(body);
            Object data = output.get("data");
            if (!(output.get("toolName") instanceof String) || data == null
                    || !(output.get("stateUpdates") instanceof JSONObject updates)
                    || !(output.get("evidences") instanceof JSONArray evidences)) {
                return message;
            }

            // 所有独有值仍保留。只有与 data 完全相同的内容才改为引用。
            List<String> duplicateSlots = updates.entrySet().stream()
                    .filter(entry -> Objects.equals(data, entry.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();
            if (!duplicateSlots.isEmpty()) {
                duplicateSlots.forEach(updates::remove);
                output.put("stateKeysReferencingData", duplicateSlots);
            }
            for (Object entry : evidences) {
                if (entry instanceof JSONObject evidence && Objects.equals(data, evidence.get("payload"))) {
                    evidence.remove("payload");
                    evidence.put("payloadRef", "data");
                }
            }
            String compact = OBSERVATION_PREFIX + output.toJSONString();
            return compact.length() < message.getContent().length() ? Message.user(compact) : message;
        } catch (JSONException exception) {
            // 旧会话和错误 Observation 可能不是平台工具输出，继续使用其原始内容。
            return message;
        }
    }
}
