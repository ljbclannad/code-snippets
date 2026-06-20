package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldCallAnalyzeEndpointAndParseResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/patient-tasks/analyze", new JsonHandler("""
                {
                  "agentSessionId": "agent-session-1",
                  "taskId": "task-1",
                  "conversationId": "conv-1",
                  "correlationId": "corr-1",
                  "status": "PENDING_APPROVAL",
                  "taskType": "PATIENT_FOLLOW_UP_ANALYSIS",
                  "patientSafeReply": "已同步给医生进一步评估，请先按当前安全建议观察。",
                  "needsHumanReview": true
                }
                """));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        HttpDoctorAssistantAgentGateway gateway = new HttpDoctorAssistantAgentGateway(
                RestClient.builder().baseUrl("http://localhost:" + server.getAddress().getPort()).build(),
                new ObjectMapper(),
                true,
                "http://localhost:" + server.getAddress().getPort(),
                5000);

        DoctorAssistantTaskResponse response = gateway.analyzePatientTask(DoctorAssistantTaskRequest.builder()
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .conversationId("conv-1")
                .correlationId("corr-1")
                .build());

        assertEquals(DoctorAssistantTaskStatus.PENDING_APPROVAL, response.getStatus());
        assertTrue(response.getNeedsHumanReview());
        assertEquals("agent-session-1", response.getAgentSessionId());
    }

    @Test
    void shouldThrowWhenGatewayDisabled() {
        HttpDoctorAssistantAgentGateway gateway = new HttpDoctorAssistantAgentGateway(
                RestClient.builder().baseUrl("http://localhost:65535").build(),
                new ObjectMapper(),
                false,
                "http://localhost:65535",
                5000);

        AiServiceException exception = assertThrows(AiServiceException.class,
                () -> gateway.analyzePatientTask(DoctorAssistantTaskRequest.builder().build()));

        assertTrue(exception.getMessage().contains("doctor-assistant-agent 未启用"));
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
