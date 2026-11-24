package com.health.agent.engine;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.health.agent.model.Message;
import com.lx.governance.agentplatform.model.PlatformContextEvidence;
import com.lx.governance.agentplatform.model.PlatformToolOutput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActPromptHistoryTest {

    @Test
    void compactsOnlyRedundantPayloadsAndKeepsOriginalHistoryAndLatestObservation() {
        String prefix = ReActPromptHistory.OBSERVATION_PREFIX;
        var datesAndWeights = List.of(
                Map.of("date", "2026-10-05", "weight", 69.4),
                Map.of("date", "2026-10-24", "weight", 68.6),
                Map.of("date", "2026-11-11", "weight", 68.9));
        var evidenceForWindow = Map.of("windowDays", 30, "recordCount", 3);
        PlatformToolOutput output = PlatformToolOutput.builder()
                .toolName("get_weight_history")
                .summary("查询体重历史")
                .data(datesAndWeights)
                .stateUpdates(Map.of("weight_history", datesAndWeights, "weight_query", evidenceForWindow))
                .evidence(PlatformContextEvidence.builder().source("WEIGHT_HISTORY").payload(datesAndWeights).build())
                .evidence(PlatformContextEvidence.builder().source("QUERY_WINDOW").payload(evidenceForWindow).build())
                .build();
        Message earlier = Message.user(prefix + JSON.toJSONString(output));
        Message invalid = Message.user(prefix + "{旧格式");
        Message blocked = Message.user(prefix + "SAFETY_BLOCK: 需要人工复核");
        Message latest = Message.user(prefix + JSON.toJSONString(output) + " ");
        List<Message> history = List.of(Message.user("请分析体重趋势"), earlier, invalid, blocked, latest);

        List<Message> projected = ReActPromptHistory.forModel(history);

        assertEquals(history.get(0), projected.get(0));
        assertEquals(earlier, history.get(1));
        assertEquals(List.of(invalid, blocked, latest), projected.subList(2, projected.size()));
        assertTrue(projected.get(1).getContent().length() < earlier.getContent().length());
        JSONObject compact = JSON.parseObject(projected.get(1).getContent().substring(prefix.length()));
        JSONObject original = JSON.parseObject(earlier.getContent().substring(prefix.length()));
        assertEquals(original.get("data"), compact.get("data"));
        assertEquals(List.of("weight_history"), compact.getJSONArray("stateKeysReferencingData"));
        assertEquals(Set.of("weight_query"), compact.getJSONObject("stateUpdates").keySet());
        assertEquals("WEIGHT_HISTORY", compact.getJSONArray("evidences").getJSONObject(0).getString("source"));
        assertEquals("data", compact.getJSONArray("evidences").getJSONObject(0).getString("payloadRef"));
        assertFalse(compact.getJSONArray("evidences").getJSONObject(0).containsKey("payload"));
        assertEquals(original.getJSONArray("evidences").getJSONObject(1),
                compact.getJSONArray("evidences").getJSONObject(1));
    }
}
