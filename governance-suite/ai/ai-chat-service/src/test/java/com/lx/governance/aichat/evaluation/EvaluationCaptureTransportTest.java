package com.lx.governance.aichat.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatResponse;
import com.lx.governance.aichat.dto.DoctorAssistantTaskResponse;
import com.lx.governance.agentplatform.model.PlatformTraceContext;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 本机 HTTP 契约验证，不使用模型或患者服务。 */
class EvaluationCaptureTransportTest {
    @Test
    void collectorFetchesReplayAndPostsCompatibleBatchObservationOverHttp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/runs/run-http/replay", exchange -> {
            byte[] data = "{\"runId\":\"run-http\",\"run\":{\"status\":\"COMPLETED\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.createContext("/api/evaluation/batches/batch-http/observations", exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            EvaluationCaptureProperties properties = new EvaluationCaptureProperties();
            properties.setEnabled(true);
            properties.setHashKey("isolated-http-test-secret");
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            properties.setBaseUrl(base);
            properties.setAgentBaseUrl(base);
            ChatEvaluationCollector collector = new ChatEvaluationCollector(properties, mapper,
                    new EvaluationCaptureTransport(properties, mapper), new SimpleMeterRegistry(), Runnable::run);
            ChatRequest request = new ChatRequest();
            request.setMessage("固定问题");
            request.setConversationId("conversation-http");
            request.setEvaluation(new EvaluationBinding("batch-http", "case-http", 1));
            collector.begin(request);
            collector.agent(request, DoctorAssistantTaskResponse.builder().traceContext(
                    PlatformTraceContext.builder().runId("run-http").build()).build());
            collector.complete(request, ChatResponse.success("实际返回内容", "conversation-http"), null);
            JsonNode observation = received.get();
            assertNotNull(observation);
            assertEquals("case-http", observation.path("caseId").asText());
            assertEquals(1, observation.path("trial").asInt());
            assertEquals("固定问题", observation.at("/input/message").asText());
            assertEquals("实际返回内容", observation.at("/output/response/message").asText());
            assertEquals("AVAILABLE", observation.at("/output/capture/replayStatus").asText());
            assertTrue(observation.path("error").isNull());
            assertEquals(8, observation.size());
        } finally { server.stop(0); }
    }
}
