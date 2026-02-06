package com.example.dify;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.util.UUID;

public class AgentTest {
    public static void main(String[] args) throws Exception {
        String apiKey = DifyConfig.apiKey();
        String baseUrl = DifyConfig.baseUrl();
        String user = DifyConfig.user();
        String query = args.length > 0 ? args[0] : "hello from java";
        String inputsJson = args.length > 1 ? args[1] : "{}";
        String conversationId = DifyConfig.conversationId();
        String traceId = UUID.randomUUID().toString();

        StringBuilder body = new StringBuilder();
        body.append("{");
        body.append("\"inputs\":").append(inputsJson).append(",");
        body.append("\"query\":\"").append(DifyJson.escape(query)).append("\",");
        body.append("\"response_mode\":\"blocking\",");
        if (!traceId.isEmpty()) {
            body.append("\"trace_id\":\"").append(DifyJson.escape(traceId)).append("\",");
        }
        if (!conversationId.isEmpty()) {
            body.append("\"conversation_id\":\"").append(DifyJson.escape(conversationId)).append("\",");
        }
        body.append("\"user\":\"").append(DifyJson.escape(user)).append("\"");
        body.append("}");

        DifyHttp http = new DifyHttp(baseUrl, apiKey);
        String response = http.post("/v1/chat-messages", body.toString(), traceId);
        System.out.println(formatResponse(response));
    }

    private static String formatResponse(String responseJson) {
        try {
            JSONObject root = JSON.parseObject(responseJson);
            JSONObject data = root.getJSONObject("data");
            if (data == null) {
                return JSON.toJSONString(root, JSONWriter.Feature.PrettyFormat);
            }
            JSONObject outputs = data.getJSONObject("outputs");
            if (outputs == null) {
                return JSON.toJSONString(root, JSONWriter.Feature.PrettyFormat);
            }
            String text = outputs.getString("text");
            if (text == null || text.trim().isEmpty()) {
                return JSON.toJSONString(root, JSONWriter.Feature.PrettyFormat);
            }
            String trimmed = text.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                Object parsed = JSON.parse(trimmed);
                return JSON.toJSONString(parsed, JSONWriter.Feature.PrettyFormat);
            }
            return text;
        } catch (Exception e) {
            return responseJson;
        }
    }
}
