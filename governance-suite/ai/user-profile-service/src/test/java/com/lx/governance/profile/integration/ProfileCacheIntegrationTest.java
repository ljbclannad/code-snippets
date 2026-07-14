package com.lx.governance.profile.integration;

import com.lx.governance.profile.config.TestRedisConfig;
import com.lx.governance.profile.dto.UserProfileDTO;
import com.lx.governance.profile.service.RedisProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试：验证完整的缓存流程和 TTL 机制
 *
 * 测试范围：
 * - RedisProfileService
 * - RedisTemplate
 * - 真实的 Redis 连接
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "ai.external.tests", matches = "true")
@ActiveProfiles("external-test")
@Import(TestRedisConfig.class)
class ProfileCacheIntegrationTest {

    @Autowired
    private RedisProfileService redisProfileService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String TEST_CUSTOMER_ID = "integration-test-001";
    private static final String CACHE_KEY = "ai:profile:" + TEST_CUSTOMER_ID;

    @AfterEach
    void cleanup() {
        // 清理测试数据
        redisTemplate.delete(CACHE_KEY);
        redisTemplate.delete(CACHE_KEY + ":null");
    }

    /**
     * 测试完整的缓存流程
     *
     * 验证点：
     * 1. 首次获取触发缓存写入
     * 2. Redis 中存在缓存数据
     * 3. 再次获取命中缓存
     * 4. 刷新缓存后数据仍然存在
     */
    @Test
    void testProfileCacheFlow() {
        // 1. 清理测试数据（确保干净的测试环境）
        redisTemplate.delete(CACHE_KEY);

        // 2. 首次获取（缓存未命中，应该从 MySQL 构建并写入 Redis）
        UserProfileDTO profile1 = redisProfileService.getProfile(TEST_CUSTOMER_ID);
        assertNotNull(profile1, "首次获取应该返回画像数据");
        assertNotNull(profile1.getBasicInfo(), "基本信息不应为空");
        assertEquals(TEST_CUSTOMER_ID, profile1.getBasicInfo().getCustomerId(),
                     "customerId 应该匹配");

        // 3. 验证 Redis 中已缓存
        Boolean exists = redisTemplate.hasKey(CACHE_KEY);
        assertTrue(exists, "Redis 中应该存在缓存数据");

        // 4. 再次获取（缓存命中，应该从 Redis 返回）
        UserProfileDTO profile2 = redisProfileService.getProfile(TEST_CUSTOMER_ID);
        assertNotNull(profile2, "再次获取应该返回画像数据");
        assertNotNull(profile2.getBasicInfo(), "基本信息不应为空");
        assertEquals(profile1.getBasicInfo().getCustomerId(),
                     profile2.getBasicInfo().getCustomerId(),
                     "两次获取的 customerId 应该一致");

        // 5. 刷新缓存
        redisProfileService.refreshProfile(TEST_CUSTOMER_ID);

        // 6. 验证缓存仍然存在
        exists = redisTemplate.hasKey(CACHE_KEY);
        assertTrue(exists, "刷新后缓存应该仍然存在");

        // 7. 验证刷新后可以正常获取
        UserProfileDTO profile3 = redisProfileService.getProfile(TEST_CUSTOMER_ID);
        assertNotNull(profile3, "刷新后应该能正常获取画像");
        assertEquals(TEST_CUSTOMER_ID, profile3.getBasicInfo().getCustomerId(),
                     "刷新后 customerId 应该保持一致");
    }

    /**
     * 测试缓存 TTL 设置
     *
     * 验证点：
     * 1. 缓存写入后设置了正确的 TTL
     * 2. TTL 应该 <= 86400 秒（24小时）
     */
    @Test
    void testCacheTTL() {
        // 1. 清理测试数据
        redisTemplate.delete(CACHE_KEY);

        // 2. 获取画像（触发缓存写入）
        UserProfileDTO profile = redisProfileService.getProfile(TEST_CUSTOMER_ID);
        assertNotNull(profile, "应该成功获取画像");

        // 3. 检查 TTL
        Long ttl = redisTemplate.getExpire(CACHE_KEY, TimeUnit.SECONDS);
        assertNotNull(ttl, "TTL 不应为空");
        assertTrue(ttl > 0, "TTL 应该大于 0");
        assertTrue(ttl <= 86400, "TTL 应该 <= 86400 秒（24小时）");

        // 验证 TTL 在合理范围内（考虑执行时间，应该接近 24 小时）
        assertTrue(ttl >= 86300, "TTL 应该接近 86400 秒（允许 100 秒误差）");
    }
}
