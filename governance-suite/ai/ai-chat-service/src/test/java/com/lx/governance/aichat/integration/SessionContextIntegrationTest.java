package com.lx.governance.aichat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.lx.governance.aichat.client.DeepSeekClient;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatResponse;
import com.lx.governance.aichat.dto.DifyResponse;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.mapper.AiChatHistoryMapper;
import com.lx.governance.aichat.repository.ChatHistorySearchRepository;
import com.lx.governance.aichat.service.AiChatService;
import com.lx.governance.aichat.service.ChatHistorySearchService;
import com.lx.governance.aichat.service.SessionContextService;

/**
 * SessionContext端到端集成测试
 * 验证从AiChatService到SessionContextService的完整流程
 */
@SpringBootTest
@ActiveProfiles("test")
class SessionContextIntegrationTest {

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private SessionContextService sessionContextService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockBean
    private DeepSeekClient deepSeekClient;

    @MockBean
    private ChatHistorySearchRepository chatHistorySearchRepository;

    @MockBean
    private ChatHistorySearchService chatHistorySearchService;

    @MockBean
    private AiChatHistoryMapper aiChatHistoryMapper;

    @BeforeEach
    void setUp() {
        // 清理Redis测试数据
        redisTemplate.keys("session:context:*").forEach(key -> redisTemplate.delete(key));
    }

    @Test
    void testEndToEndChatFlow_WithSessionContext() throws Exception {
        // 1. 准备测试数据
        String conversationId = "test-conv-001";
        String customerId = "customer-001";
        String groupId = "group-001";
        String userMessage = "我最近血压有点高，应该注意什么？";

        ChatRequest request = new ChatRequest();
        request.setConversationId(conversationId);
        request.setCustomerId(customerId);
        request.setGroupId(groupId);
        request.setMessage(userMessage);

        // 2. Mock DeepSeek响应
        DifyResponse mockResponse = new DifyResponse();
        mockResponse.setAnswer("建议您注意低盐饮食，适量运动，保持良好作息。");
        mockResponse.setConversationId(conversationId);
        when(deepSeekClient.chat(any(), any(), any(),
                org.mockito.ArgumentMatchers.<MemoryItemDTO>anyList())).thenReturn(mockResponse);

        // 3. 第一次对话 - 创建会话上下文
        ChatResponse response1 = aiChatService.handleChatRequest(request).get();

        assertThat(response1).isNotNull();
        assertThat(response1.getSuccess()).isTrue();
        assertThat(response1.getMessage()).isEqualTo(mockResponse.getAnswer());

        // 4. 验证会话上下文已创建
        SessionContext context1 = sessionContextService.getOrCreateContext(
                conversationId, customerId, groupId, userMessage);

        assertThat(context1).isNotNull();
        assertThat(context1.getConversationId()).isEqualTo(conversationId);
        assertThat(context1.getCustomerId()).isEqualTo(customerId);
        assertThat(context1.getGroupId()).isEqualTo(groupId);
        assertThat(context1.getMessageCount()).isGreaterThan(0);

        // 5. 第二次对话 - 更新会话上下文
        String userMessage2 = "那我需要吃药吗？";
        request.setMessage(userMessage2);

        DifyResponse mockResponse2 = new DifyResponse();
        mockResponse2.setAnswer("建议您先咨询医生，根据血压值和身体状况决定是否需要用药。");
        mockResponse2.setConversationId(conversationId);
        when(deepSeekClient.chat(any(), any(), any(),
                org.mockito.ArgumentMatchers.<MemoryItemDTO>anyList())).thenReturn(mockResponse2);

        ChatResponse response2 = aiChatService.handleChatRequest(request).get();

        assertThat(response2).isNotNull();
        assertThat(response2.getSuccess()).isTrue();

        // 6. 验证会话上下文已更新
        SessionContext context2 = sessionContextService.getOrCreateContext(
                conversationId, customerId, groupId, userMessage2);

        assertThat(context2).isNotNull();
        assertThat(context2.getMessageCount()).isGreaterThan(context1.getMessageCount());
        assertThat(context2.getRecentSummaries()).isNotEmpty();
    }

    @Test
    void testSessionContext_MultipleConversations() {
        // 测试多个独立会话的上下文隔离
        String customerId = "customer-002";
        String groupId = "group-002";

        // 会话1
        String conv1 = "conv-001";
        ChatRequest request1 = new ChatRequest();
        request1.setConversationId(conv1);
        request1.setCustomerId(customerId);
        request1.setGroupId(groupId);
        request1.setMessage("我想了解糖尿病");

        DifyResponse mockResponse1 = new DifyResponse();
        mockResponse1.setAnswer("糖尿病是一种慢性代谢疾病...");
        mockResponse1.setConversationId(conv1);
        when(deepSeekClient.chat(any(), any(), any(),
                org.mockito.ArgumentMatchers.<MemoryItemDTO>anyList())).thenReturn(mockResponse1);

        aiChatService.handleChatRequest(request1);

        // 会话2
        String conv2 = "conv-002";
        ChatRequest request2 = new ChatRequest();
        request2.setConversationId(conv2);
        request2.setCustomerId(customerId);
        request2.setGroupId(groupId);
        request2.setMessage("我想了解高血压");

        DifyResponse mockResponse2 = new DifyResponse();
        mockResponse2.setAnswer("高血压是一种常见的心血管疾病...");
        mockResponse2.setConversationId(conv2);
        when(deepSeekClient.chat(any(), any(), any(),
                org.mockito.ArgumentMatchers.<MemoryItemDTO>anyList())).thenReturn(mockResponse2);

        aiChatService.handleChatRequest(request2);

        // 验证两个会话的上下文是独立的
        SessionContext context1 = sessionContextService.getOrCreateContext(
                conv1, customerId, groupId, "test");
        SessionContext context2 = sessionContextService.getOrCreateContext(
                conv2, customerId, groupId, "test");

        assertThat(context1.getConversationId()).isEqualTo(conv1);
        assertThat(context2.getConversationId()).isEqualTo(conv2);
        assertThat(context1.getConversationId()).isNotEqualTo(context2.getConversationId());
    }

    @Test
    void testSessionContext_Persistence() {
        // 测试会话上下文的持久化
        String conversationId = "test-conv-persist";
        String customerId = "customer-003";
        String groupId = "group-003";
        String userMessage = "测试持久化";

        ChatRequest request = new ChatRequest();
        request.setConversationId(conversationId);
        request.setCustomerId(customerId);
        request.setGroupId(groupId);
        request.setMessage(userMessage);

        DifyResponse mockResponse = new DifyResponse();
        mockResponse.setAnswer("测试响应");
        mockResponse.setConversationId(conversationId);
        when(deepSeekClient.chat(any(), any(), any(),
                org.mockito.ArgumentMatchers.<MemoryItemDTO>anyList())).thenReturn(mockResponse);

        // 创建会话
        aiChatService.handleChatRequest(request);

        // 验证Redis中存在该会话上下文
        String redisKey = "session:context:" + conversationId;
        String contextJson = redisTemplate.opsForValue().get(redisKey);

        assertThat(contextJson).isNotNull();
        assertThat(contextJson).contains(conversationId);
        assertThat(contextJson).contains(customerId);
    }
}
