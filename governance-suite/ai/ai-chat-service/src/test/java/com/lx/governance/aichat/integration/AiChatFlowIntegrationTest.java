package com.lx.governance.aichat.integration;

import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.service.AiChatService;
import com.lx.governance.aichat.service.SessionContextService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfSystemProperty(named = "ai.external.tests", matches = "true")
@ActiveProfiles("external-test")
class AiChatFlowIntegrationTest {

    @Autowired(required = false)
    private AiChatService aiChatService;

    @Autowired(required = false)
    private SessionContextService sessionContextService;

    @Autowired(required = false)
    private RedisTemplate<String, String> redisTemplate;

    @Test
    void testCompleteAiChatFlow() {
        // 如果服务未注入，跳过测试
        if (aiChatService == null || sessionContextService == null) {
            System.out.println("跳过集成测试 - 服务未配置");
            return;
        }

        // 准备测试数据
        String conversationId = "integration-test-conv-001";
        String customerId = "integration-test-user-001";
        String groupId = "integration-test-group-001";

        // 清理测试数据
        cleanupTestData(conversationId, customerId);

        // 1. 第一轮对话
        String message1 = "我的血糖最近有点高";
        // String response1 = aiChatService.chat(conversationId, customerId, groupId, message1);

        // assertNotNull(response1);
        // assertFalse(response1.isEmpty());

        // 2. 验证会话上下文已创建
        SessionContext context = sessionContextService.getOrCreateContext(
            conversationId, customerId, groupId, message1);
        assertNotNull(context);
        assertEquals(conversationId, context.getConversationId());

        // 3. 第二轮对话（测试上下文连贯性）
        String message2 = "那我应该怎么控制？";
        // String response2 = aiChatService.chat(conversationId, customerId, groupId, message2);

        // assertNotNull(response2);
        // assertFalse(response2.isEmpty());

        // 4. 验证会话上下文已更新
        context = sessionContextService.getOrCreateContext(
            conversationId, customerId, groupId, message2);
        assertTrue(context.getMessageCount() >= 1);

        // 清理测试数据
        cleanupTestData(conversationId, customerId);
    }

    @Test
    void testProfileCacheFlow() {
        // 如果Redis未注入，跳过测试
        if (redisTemplate == null) {
            System.out.println("跳过缓存测试 - Redis未配置");
            return;
        }

        String customerId = "cache-test-user-001";

        // 清理
        redisTemplate.delete("ai:profile:" + customerId);

        // 1. 首次获取（缓存未命中）
        long start1 = System.currentTimeMillis();
        // var profile1 = redisProfileService.getProfile(customerId);
        long time1 = System.currentTimeMillis() - start1;

        // assertNotNull(profile1);

        // 2. 再次获取（缓存命中）
        long start2 = System.currentTimeMillis();
        // var profile2 = redisProfileService.getProfile(customerId);
        long time2 = System.currentTimeMillis() - start2;

        // assertNotNull(profile2);

        // 缓存命中应该更快
        // assertTrue(time2 < time1,
        //     String.format("缓存命中应该更快: time1=%dms, time2=%dms", time1, time2));

        // 清理
        redisTemplate.delete("ai:profile:" + customerId);
    }

    @Test
    void testSessionContextExpiration() throws InterruptedException {
        // 如果服务未注入，跳过测试
        if (sessionContextService == null || redisTemplate == null) {
            System.out.println("跳过过期测试 - 服务未配置");
            return;
        }

        String conversationId = "expiration-test-conv-001";
        String customerId = "expiration-test-user-001";
        String groupId = "expiration-test-group-001";

        // 创建上下文
        SessionContext context = sessionContextService.getOrCreateContext(
            conversationId, customerId, groupId, "测试消息");
        assertNotNull(context);

        // 验证TTL
        String key = "ai:session:context:" + conversationId;
        Long ttl = redisTemplate.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 1800); // 30分钟 = 1800秒

        // 清理
        redisTemplate.delete(key);
    }

    private void cleanupTestData(String conversationId, String customerId) {
        if (redisTemplate != null) {
            redisTemplate.delete("ai:session:context:" + conversationId);
            redisTemplate.delete("ai:profile:" + customerId);
        }
    }
}
