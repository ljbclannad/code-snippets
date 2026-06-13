package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.lx.governance.aichat.dto.ConversationHistoryResponseDTO;
import com.lx.governance.aichat.dto.ConversationSummaryDTO;
import com.lx.governance.aichat.entity.AiChatHistory;
import com.lx.governance.aichat.mapper.AiChatHistoryMapper;

@ExtendWith(MockitoExtension.class)
class ChatHistoryServiceTest {

    @Mock
    private AiChatHistoryMapper chatHistoryMapper;

    private ChatHistoryService chatHistoryService;

    @BeforeEach
    void setUp() {
        chatHistoryService = new ChatHistoryService(chatHistoryMapper);
    }

    @Test
    void shouldListConversationSummariesGroupedByConversationId() {
        when(chatHistoryMapper.selectList(any())).thenReturn(List.of(
                history(3L, "customer-1", "conv-b", "最近睡不好", "可以先调整作息", LocalDateTime.now().minusHours(1)),
                history(2L, "customer-1", "conv-a", "早餐吃什么", "建议高蛋白早餐", LocalDateTime.now().minusHours(2)),
                history(1L, "customer-1", "conv-a", "昨天血糖高", "先记录数值", LocalDateTime.now().minusHours(3))));

        List<ConversationSummaryDTO> results = chatHistoryService.listConversations("customer-1", 0, 20);

        assertEquals(2, results.size());
        assertEquals("conv-b", results.get(0).getConversationId());
        assertEquals(1, results.get(0).getRounds());
        assertEquals("conv-a", results.get(1).getConversationId());
        assertEquals(2, results.get(1).getRounds());
        assertEquals("早餐吃什么", results.get(1).getLatestUserMessage());
    }

    @Test
    void shouldReturnFlattenedConversationMessagesInTimeOrder() {
        LocalDateTime first = LocalDateTime.now().minusHours(2);
        LocalDateTime second = LocalDateTime.now().minusHours(1);

        when(chatHistoryMapper.selectList(any())).thenReturn(List.of(
                history(1L, "customer-1", "conv-a", "早餐怎么吃", "建议少糖高蛋白", first),
                history(2L, "customer-1", "conv-a", "晚饭呢", "晚饭注意总量", second)));

        ConversationHistoryResponseDTO response = chatHistoryService.getConversationHistory("customer-1", "conv-a");

        assertEquals("conv-a", response.getConversationId());
        assertEquals(2, response.getRounds());
        assertEquals(4, response.getMessages().size());
        assertEquals("早餐怎么吃", response.getMessages().get(0).getContent());
        assertEquals("建议少糖高蛋白", response.getMessages().get(1).getContent());
        assertEquals("晚饭呢", response.getMessages().get(2).getContent());
        assertEquals("晚饭注意总量", response.getMessages().get(3).getContent());
    }

    @Test
    void shouldRejectBlankCustomerId() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> chatHistoryService.listConversations(" ", 0, 20));
        assertEquals("customerId cannot be blank", ex.getMessage());
    }

    @Test
    void shouldRejectUnknownConversation() {
        when(chatHistoryMapper.selectList(any())).thenReturn(List.of());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> chatHistoryService.getConversationHistory("customer-1", "missing"));
        assertEquals("Conversation not found: missing", ex.getMessage());
    }

    private AiChatHistory history(
            Long id,
            String customerId,
            String conversationId,
            String userMessage,
            String aiResponse,
            LocalDateTime createTime) {
        AiChatHistory history = new AiChatHistory();
        history.setId(id);
        history.setCustomerId(customerId);
        history.setConversationId(conversationId);
        history.setUserMessage(userMessage);
        history.setAiResponse(aiResponse);
        history.setCreateTime(createTime);
        history.setIsDeleted(0);
        return history;
    }
}
