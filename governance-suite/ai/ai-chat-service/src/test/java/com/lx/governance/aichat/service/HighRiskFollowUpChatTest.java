package com.lx.governance.aichat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.metrics.AiChatMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HighRiskFollowUpChatTest {
    private final RestClient.Builder client = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
    private final AgentDispatchPolicy dispatch = mock(AgentDispatchPolicy.class);
    private final LoadBalancerClient loadBalancer = mock(LoadBalancerClient.class);
    private final DoctorAssistantAgentEndpoint endpoint = new DoctorAssistantAgentEndpoint(
            loadBalancer, "doctor-assistant-agent", "");
    private final HighRiskFollowUpChat chat = new HighRiskFollowUpChat(client.build(), endpoint,
            mock(AiChatMetrics.class), dispatch, true, "test-token",
            mock(HighRiskFollowUpHistoryService.class), mock(SessionContextService.class));

    @BeforeEach
    void registerDoctorInstance() {
        when(loadBalancer.choose("doctor-assistant-agent")).thenReturn(new DefaultServiceInstance(
                "doctor-1", "doctor-assistant-agent", "127.0.0.1", 8088, false));
    }

    @Test void onlyRegisteredHighRiskRequestsReceivePendingConfirmation() {
        assertTrue(chat.handles("你好，我现在胸痛"));
        assertFalse(chat.handles("我想了解随访流程"));
        var request = request();
        String conversationId = chat.conversationId(request);
        assertEquals(conversationId, chat.conversationId(request()));
        when(dispatch.evaluate(eq(request), isNull(), any())).thenReturn(new AgentDispatchPolicy.Decision(true, "ok"));
        server.expect(requestTo("http://127.0.0.1:8088/internal/patient-tasks/high-risk-follow-up"))
                .andExpect(header("X-Follow-Up-Token", "test-token"))
                .andExpect(jsonPath("$.requestId").value("r1"))
                .andRespond(withSuccess("{\"taskId\":7,\"status\":\"PENDING\"}", MediaType.APPLICATION_JSON));
        var result = chat.reply(request);
        assertTrue(result.message().contains("等待责任医生处理"));
        assertEquals("PENDING", result.followUp().status());
        server.verify();
    }

    @Test void timeoutOrUnverifiedIdentityCannotConfirmRegistration() throws Exception {
        var forged = new ObjectMapper().readValue("{\"verifiedPatient\":true,\"customerId\":\"p1\"}", ChatRequest.class);
        assertFalse(forged.isVerifiedPatient());
        var guestReply = chat.reply(forged);
        assertTrue(guestReply.message().contains("无法确认"));
        assertNull(guestReply.followUp());
        var request = request();
        chat.conversationId(request);
        when(dispatch.evaluate(eq(request), isNull(), any())).thenReturn(new AgentDispatchPolicy.Decision(false, "circuit_open"));
        assertTrue(chat.reply(request).message().contains("无法确认"));
        when(dispatch.evaluate(eq(request), isNull(), any())).thenReturn(new AgentDispatchPolicy.Decision(true, "ok"));
        server.expect(requestTo("http://127.0.0.1:8088/internal/patient-tasks/high-risk-follow-up")).andRespond(withGatewayTimeout());
        assertEquals("UNCONFIRMED", chat.reply(request).followUp().status());
        server.verify();
    }

    @Test void statusFollowUpReadsWithoutCreatingOrDispatchingAnotherTask() {
        var request = request();
        request.setConversationId("c1");
        request.setRequestId(null);
        request.setMessage("刚才那条医生看了吗？");
        assertTrue(chat.handles(request.getMessage()));
        assertFalse(chat.handles("医生看了吗，我还想问一下晚饭怎么吃"));
        server.expect(requestTo("http://127.0.0.1:8088/internal/patient-tasks/review-status?customerId=p1&conversationId=c1"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("X-Follow-Up-Token", "test-token"))
                .andRespond(withSuccess("{\"status\":\"APPROVED\",\"delivered\":false}", MediaType.APPLICATION_JSON));
        assertEquals("医生已批准本次复核任务。", chat.reply(request).message());
        verifyNoInteractions(dispatch);
        server.verify();
    }

    private ChatRequest request() {
        var request = new ChatRequest();
        request.setCustomerId("p1");
        request.setRequestId("r1");
        request.setMessage("胸痛");
        request.setVerifiedPatient(true);
        return request;
    }
}
