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
}
