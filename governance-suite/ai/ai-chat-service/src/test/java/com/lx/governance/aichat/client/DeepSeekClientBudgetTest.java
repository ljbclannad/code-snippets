package com.lx.governance.aichat.client;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.context.ContextBudgetManager;
import com.lx.governance.aichat.context.DynamicBudgetStrategy;
import com.lx.governance.aichat.context.PromptBudgetPolicy;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.StreamEvent;
import com.lx.governance.aichat.exception.ConversationException;
import com.lx.governance.aichat.monitor.TokenMonitor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeepSeekClientBudgetTest {
    @Test
    void oversizedInputStopsBothModelEntryPoints() {
        var properties = new ContextBudgetProperties();
        var budget = new ContextBudgetManager(
                new PromptBudgetPolicy(properties, new DynamicBudgetStrategy()), List.of());
        var model = mock(ChatModel.class);
        var streaming = mock(StreamingChatModel.class);
        var client = new DeepSeekClient(model, streaming, budget, mock(TokenMonitor.class), properties);
        var request = new ChatRequest();
        request.setMessage("测试".repeat(3000));
        var memories = LayeredMemories.builder().build();
        assertThrows(ConversationException.class, () -> client.chat(request, null, null, memories));
        var events = client.chatStream(request, null, null, memories).collectList().block();
        assertEquals(1, events.size());
        assertEquals(StreamEvent.EventType.ERROR, events.get(0).getType());
        assertEquals(2003, events.get(0).getErrorCode());
        assertTrue(events.get(0).getError().contains("分段"));
        verifyNoInteractions(model, streaming);
    }
}
