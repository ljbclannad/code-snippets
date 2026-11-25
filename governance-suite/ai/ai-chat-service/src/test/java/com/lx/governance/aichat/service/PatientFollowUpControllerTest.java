package com.lx.governance.aichat.service;

import com.lx.governance.aichat.controller.PatientFollowUpController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PatientFollowUpControllerTest {
    @Test void usesTrustedPatientSessionAndChecksConversationOwnershipBeforeReading() throws Exception {
        var access = mock(ChatConversationAccess.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(access).requireOwnedConversation("other", "demo-patient");
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var endpoint = new DoctorAssistantAgentEndpoint(null, "doctor", "http://127.0.0.1:8088");
        var mvc = MockMvcBuilders.standaloneSetup(new PatientFollowUpController(access, builder.build(), endpoint, "test-token")).build();
        mvc.perform(get("/api/patient/session")).andExpect(jsonPath("$.customerId").doesNotExist());
        mvc.perform(get("/api/patient/session").with(actor("PATIENT"))).andExpect(jsonPath("$.customerId").value("demo-patient"));
        mvc.perform(get("/api/patient/follow-up-reminders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/patient/follow-up-progress").param("conversationId", "ours").with(actor("DOCTOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/patient/follow-up-progress").param("conversationId", "other").with(actor("PATIENT")))
                .andExpect(status().isForbidden());

        server.expect(requestTo("http://127.0.0.1:8088/internal/patient-tasks/follow-up-status?customerId=demo-patient&conversationId=ours"))
                .andExpect(header("X-Follow-Up-Token", "test-token"))
                .andRespond(withSuccess("{\"status\":\"APPROVED\",\"delivered\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://127.0.0.1:8088/internal/patient-tasks/follow-up-inbox?customerId=demo-patient&beforeId=8"))
                .andExpect(header("X-Follow-Up-Token", "test-token"))
                .andRespond(withSuccess("[{\"receiptId\":\"7\",\"message\":\"提醒\",\"deliveredAt\":\"2026-11-25T14:00:00\"}]",
                        MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/patient/follow-up-progress").param("conversationId", "ours").with(actor("PATIENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.delivered").value(true));
        mvc.perform(get("/api/patient/follow-up-reminders").param("customerId", "spoofed").param("beforeId", "8").with(actor("PATIENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].receiptId").value("7"));
        server.verify();
    }

    private static RequestPostProcessor actor(String role) {
        return request -> {
            request.setUserPrincipal(() -> "demo-patient");
            request.addUserRole(role);
            return request;
        };
    }
}
