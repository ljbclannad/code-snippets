package com.health.agent.engine;

import com.health.agent.exception.AgentException;
import com.health.agent.model.ThoughtResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActResponseParserTest {

    private final ReActResponseParser parser = new ReActResponseParser();

    @Test
    void shouldParseStrictJsonActionProtocol() {
        String response = """
            {
              "protocolVersion": "v1",
              "thinking": "先查询患者基本信息",
              "finished": false,
              "action": {
                "toolName": "get_patient_profile",
                "input": {
                  "customerId": "1001"
                },
                "reason": "需要获取基础资料"
              }
            }
            """;

        ThoughtResult result = parser.parse(response);

        assertFalse(result.isFinished());
        assertEquals("先查询患者基本信息", result.getThinking());
        assertEquals("get_patient_profile", result.getAction().getToolName());
        assertEquals("1001", result.getAction().getInput().get("customerId"));
        assertEquals("需要获取基础资料", result.getAction().getReason());
    }

    @Test
    void shouldRejectResponseWhenFinalAnswerAndActionBothExist() {
        String response = """
            {
              "protocolVersion": "v1",
              "thinking": "信息已经足够",
              "finished": true,
              "finalAnswer": "给出最终建议",
              "action": {
                "toolName": "get_patient_profile",
                "input": {
                  "customerId": "1001"
                }
              }
            }
            """;

        AgentException exception = assertThrows(AgentException.class, () -> parser.parse(response));

        assertTrue(exception.getMessage().contains("协议"));
    }

    @Test
    void shouldParseStructuredFinalAnswerPayload() {
        String response = """
            {
              "protocolVersion": "v1",
              "thinking": "已经收集到足够证据。",
              "finished": true,
              "finalAnswer": {
                "summary": "患者近30天减重2.5kg。",
                "keyFindings": ["体重整体下降", "晚间加餐增加"],
                "riskLevel": "MEDIUM",
                "recommendedActions": ["继续饮食记录", "增加晚间干预"],
                "needsAttention": true,
                "uncertainties": ["缺少近7天运动记录"],
                "supportingEvidence": ["近30天体重下降2.5kg", "聊天中提到晚间加餐增加"],
                "evidenceSources": ["WEIGHT_HISTORY", "CHAT_HISTORY"],
                "inferences": ["当前减重节奏受到晚间饮食影响"]
              }
            }
            """;

        ThoughtResult result = parser.parse(response);

        assertTrue(result.isFinished());
        assertEquals("患者近30天减重2.5kg。", result.getStructuredFinalAnswer().getSummary());
        assertEquals("MEDIUM", result.getStructuredFinalAnswer().getRiskLevel());
        assertEquals(2, result.getStructuredFinalAnswer().getSupportingEvidence().size());
        assertEquals("WEIGHT_HISTORY", result.getStructuredFinalAnswer().getEvidenceSources().get(0));
    }
}
