package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.lx.governance.aichat.dto.StreamEvent;
import org.springframework.test.util.ReflectionTestUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.DoctorAssistantTaskRequest;
import com.lx.governance.aichat.dto.DoctorAssistantTaskResponse;
import com.lx.governance.aichat.dto.DoctorAssistantTaskStatus;
import com.lx.governance.aichat.dto.TaskType;
import com.lx.governance.aichat.exception.AiServiceException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

class HttpDoctorAssistantAgentGatewayTest {

    private static final String SERVICE_ID = "doctor-assistant-agent";

    private final LoadBalancerClient loadBalancer = mock(LoadBalancerClient.class);
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void patientStreamNeverSerializesInternalStagesOrErrors() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var gateway = gateway(mapper, true);
        var root = mapper.readTree("{\"sessionId\":\"internal-secret\",\"data\":{\"message\":\"internal-secret\"}}");
        var request = DoctorAssistantTaskRequest.builder().conversationId("c1").build();
        for (String stage : List.of("THINKING_DELTA", "THOUGHT", "ACTION", "OBSERVATION", "ERROR", "FUTURE_STAGE")) {
            List<StreamEvent> events = ReflectionTestUtils.invokeMethod(gateway, "mapRemoteEvent", request, stage, root.get("data"));
            assertTrue(!mapper.writeValueAsString(events).contains("internal-secret"), stage);
        }
    }

    @Test
    void shouldKeepRunAssociationInStreamResultWithoutInternalPayload() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var gateway = gateway(mapper, true);
        var result = mapper.readTree("""
                {"status":"SUCCESS","patientSafeReply":"安全回复",
                 "traceContext":{"runId":"run-123","correlationId":"corr-123"},
                 "structuredResult":{"uncertainties":["内部分析"]}}
                """);
        List<StreamEvent> events = ReflectionTestUtils.invokeMethod(gateway, "taskResultEvents",
                DoctorAssistantTaskRequest.builder().conversationId("conv-123").build(), result);

        assertEquals("run-123", events.get(0).getEvaluationTaskResponse().getTraceContext().getRunId());
        assertEquals("corr-123", events.get(0).getEvaluationTaskResponse().getTraceContext().getCorrelationId());
        String serialized = mapper.writeValueAsString(events.get(0));
        assertTrue(!serialized.contains("evaluationTaskResponse"));
        assertTrue(!serialized.contains("structuredResult"));
        assertTrue(!serialized.contains("run-123"));
        assertTrue(!serialized.contains("corr-123"));
        assertTrue(!events.get(0).toString().contains("内部分析"));
        assertEquals("安全回复", events.get(1).getContent());
    }

    @Test
    void shouldCallAnalyzeEndpointAndParseResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/patient-tasks/analyze", new JsonHandler("""
                {
                  "agentSessionId": "agent-session-1",
                  "taskId": "task-1",
                  "conversationId": "conv-1",
                  "correlationId": "corr-1",
                  "status": "PENDING_APPROVAL",
                  "taskType": "PATIENT_FOLLOW_UP_ANALYSIS",
                  "patientSafeReply": "已同步给医生进一步评估，请先按当前安全建议观察。",
                  "patientVisibleEvidence": [
                    {
                      "title": "血糖复盘建议",
                      "summary": "建议继续记录近 3 天血糖变化。",
                      "source": "HEALTH_KNOWLEDGE",
                      "trustLevel": "HIGH"
                    }
                  ],
                  "needsHumanReview": true
                }
                """));
        server.start();
        registerTestInstance();
        var gateway = gateway(new ObjectMapper(), true);

        DoctorAssistantTaskResponse response = gateway.analyzePatientTask(DoctorAssistantTaskRequest.builder()
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .conversationId("conv-1")
                .correlationId("corr-1")
                .build());

        assertEquals(DoctorAssistantTaskStatus.PENDING_APPROVAL, response.getStatus());
        assertTrue(response.getNeedsHumanReview());
        assertEquals("agent-session-1", response.getAgentSessionId());
        assertEquals(1, response.getPatientVisibleEvidence().size());
        assertEquals("血糖复盘建议", response.getPatientVisibleEvidence().get(0).getTitle());
        verify(loadBalancer).choose(SERVICE_ID);
    }

    @Test
    void realRemoteStreamStopsAtFirstFinalResultAndRejectsTruncatedInput() throws Exception {
        var wire = new java.util.concurrent.atomic.AtomicReference<>("""
                data: {"type":"THOUGHT","data":"internal-secret"}

                data: {"type":"TASK_RESULT","data":{"status":"SUCCESS","patientSafeReply":"安全回复"}}

                data: {"type":"TASK_RESULT","data":{"status":"SUCCESS","patientSafeReply":"重复回复"}}

                """);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/patient-tasks/analyze/stream", exchange -> {
            try (exchange) {
                if (!"test-token".equals(exchange.getRequestHeaders().getFirst("X-Follow-Up-Token"))) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                byte[] body = wire.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        registerTestInstance();
        var mapper = new ObjectMapper();
        var gateway = gateway(mapper, true);
        var request = DoctorAssistantTaskRequest.builder().conversationId("c1").build();
        var events = gateway.analyzePatientTaskStream(request).collectList().block();
        assertEquals(List.of(StreamEvent.EventType.STATUS, StreamEvent.EventType.CONTENT, StreamEvent.EventType.DONE),
                events.stream().map(StreamEvent::getType).toList());
        assertEquals("安全回复", events.get(1).getContent());
        assertTrue(!mapper.writeValueAsString(events).contains("internal-secret"));
        wire.set("data: {\"type\":\"DONE\"}\n\n");
        assertThrows(AiServiceException.class, () -> gateway.analyzePatientTaskStream(request).collectList().block());
        verify(loadBalancer, times(2)).choose(SERVICE_ID);
    }

    @Test
    void shouldThrowWhenGatewayDisabled() {
        var gateway = gateway(new ObjectMapper(), false);

        AiServiceException exception = assertThrows(AiServiceException.class,
                () -> gateway.analyzePatientTask(DoctorAssistantTaskRequest.builder().build()));

        assertTrue(exception.getMessage().contains("doctor-assistant-agent 未启用"));
        verifyNoInteractions(loadBalancer);
    }

    @Test
    void shouldReportNoAvailableInstanceForBothProtocols() {
        var gateway = gateway(new ObjectMapper(), true);
        var request = DoctorAssistantTaskRequest.builder().build();

        var syncFailure = assertThrows(AiServiceException.class, () -> gateway.analyzePatientTask(request));
        var streamFailure = assertThrows(AiServiceException.class,
                () -> gateway.analyzePatientTaskStream(request).blockLast());

        assertTrue(syncFailure.getMessage().contains("Nacos 中没有可用的医生助手实例"));
        assertTrue(streamFailure.getMessage().contains("Nacos 中没有可用的医生助手实例"));
    }

    private HttpDoctorAssistantAgentGateway gateway(ObjectMapper mapper, boolean enabled) {
        var endpoint = new DoctorAssistantAgentEndpoint(loadBalancer, SERVICE_ID, "");
        return new HttpDoctorAssistantAgentGateway(RestClient.create(), mapper, enabled, endpoint, 5000, "test-token");
    }

    private void registerTestInstance() {
        when(loadBalancer.choose(SERVICE_ID)).thenReturn(new DefaultServiceInstance(
                "doctor-test", SERVICE_ID, "127.0.0.1", server.getAddress().getPort(), false));
    }

    private static class JsonHandler implements HttpHandler {
        private final String response;

        private JsonHandler(String response) {
            this.response = response;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        }
    }
}
