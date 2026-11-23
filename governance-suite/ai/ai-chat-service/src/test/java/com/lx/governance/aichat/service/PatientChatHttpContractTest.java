package com.lx.governance.aichat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.controller.AiChatController;
import com.lx.governance.aichat.controller.AiChatStreamController;
import com.lx.governance.aichat.dto.*;
import com.lx.governance.aichat.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PatientChatHttpContractTest {
    private final ChatConversationAccess access = mock(ChatConversationAccess.class);
    private final AiChatService chat = mock(AiChatService.class);
    private final AiChatStreamService stream = mock(AiChatStreamService.class);
    private final String body = "{\"groupId\":\"g1\",\"message\":\"测试\"}";
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new AiChatController(access, chat), new AiChatStreamController(access, stream))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test void bothEndpointsRejectBeforeProcessing() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(access).bind(any(), any());
        for (String url : new String[]{"/api/chat", "/api/chat/stream"}) {
            mvc.perform(post(url).contentType("application/json").content(body)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(chat, stream);
    }

    @Test void eachSseFrameCarriesOnePatientJsonObject() throws Exception {
        StreamEvent progress = StreamEvent.status("result", "分析已结束", null);
        progress.setEvaluationTaskResponse(DoctorAssistantTaskResponse.builder().taskId("internal-secret").build());
        when(stream.handleChatStreamRequest(any())).thenReturn(Flux.just(StreamEvent.start("c1"),
                progress, StreamEvent.content("安全回复"), StreamEvent.done("c1", null)));
        var exchange = mvc.perform(post("/api/chat/stream").accept("text/event-stream")
                .contentType("application/json").content(body)).andExpect(request().asyncStarted()).andReturn();
        var response = mvc.perform(asyncDispatch(exchange)).andExpect(status().isOk()).andReturn().getResponse();
        String wire = response.getContentAsString(StandardCharsets.UTF_8);
        assertFalse(wire.contains("internal-secret"));
        String[] frames = wire.strip().split("\n\n");
        assertEquals(4, frames.length);
        var mapper = new ObjectMapper();
        for (String frame : frames) {
            assertTrue(frame.startsWith("data:"));
            assertTrue(mapper.readTree(frame.substring(5)).has("type"));
        }
        assertTrue(frames[2].contains("安全回复"));
    }
}
