package com.lx.governance.vectormemory.service;

import com.lx.governance.vectormemory.application.governance.*;
import com.lx.governance.vectormemory.application.ingestion.*;
import com.lx.governance.vectormemory.application.retrieval.*;
import com.lx.governance.vectormemory.domain.candidate.*;
import com.lx.governance.vectormemory.domain.lifecycle.*;
import com.lx.governance.vectormemory.domain.retrieval.*;
import com.lx.governance.vectormemory.integration.ai.*;
import com.lx.governance.vectormemory.integration.storage.*;
import com.lx.governance.vectormemory.support.observability.*;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * RedisMemoryIndexService 单元测试
 * 测试 Redis 记忆索引和缓存功能
 *
 * @author LeJinBo
 * @since 2026-05-18
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
class RedisMemoryIndexServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private SetOperations<String, Object> setOperations;

    @Mock
    private ZSetOperations<String, Object> zSetOperations;

    @InjectMocks
    private RedisMemoryIndexService redisMemoryIndexService;

    @BeforeEach
    void setUp() {
        // 设置 dedupEnabled 为 true
        ReflectionTestUtils.setField(redisMemoryIndexService, "dedupEnabled", true);

        // 配置 RedisTemplate 返回 mock 的 operations（使用 lenient 避免 UnnecessaryStubbing 错误）
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    /**
     * 测试重复检测（内容已存在）
     */
    @Test
    void testIsDuplicate_WhenContentExists_ReturnsTrue() {
        // Given
        String groupId = "test-group-001";
        String content = "我对海鲜过敏";
        String expectedHashKey = "ai:memory:hash:test-group-001";

        // Mock: Redis 返回 true（内容已存在）
        when(setOperations.isMember(eq(expectedHashKey), anyString())).thenReturn(true);

        // When
        boolean result = redisMemoryIndexService.isDuplicate(groupId, content);

        // Then
        assertTrue(result, "Should return true when content exists");
        verify(setOperations).isMember(eq(expectedHashKey), anyString());
        log.info("Test passed: isDuplicate returns true for existing content");
    }

    /**
     * 测试重复检测（内容不存在）
     */
    @Test
    void testIsDuplicate_WhenContentNotExists_ReturnsFalse() {
        // Given
        String groupId = "test-group-002";
        String content = "我喜欢清淡饮食";
        String expectedHashKey = "ai:memory:hash:test-group-002";

        // Mock: Redis 返回 false（内容不存在）
        when(setOperations.isMember(eq(expectedHashKey), anyString())).thenReturn(false);

        // When
        boolean result = redisMemoryIndexService.isDuplicate(groupId, content);

        // Then
        assertFalse(result, "Should return false when content does not exist");
        verify(setOperations).isMember(eq(expectedHashKey), anyString());
        log.info("Test passed: isDuplicate returns false for non-existing content");
    }

    /**
     * 测试添加哈希索引成功（键不存在，设置 TTL）
     */
    @Test
    void testAddToHashIndex_Success() {
        // Given
        String groupId = "test-group-003";
        String content = "我喜欢运动健身";
        String expectedHashKey = "ai:memory:hash:test-group-003";

        // Mock: Redis 操作成功
        // 1. add() 添加成功
        when(setOperations.add(eq(expectedHashKey), anyString())).thenReturn(1L);
        // 2. 添加前键不存在，添加后键存在
        when(redisTemplate.hasKey(expectedHashKey))
                .thenReturn(false)  // 第一次调用（实现中的检查）返回 false
                .thenReturn(true);  // 后续调用返回 true（键已存在）
        when(redisTemplate.expire(eq(expectedHashKey), anyLong(), any())).thenReturn(true);

        // When
        redisMemoryIndexService.addToHashIndex(groupId, content);

        // Then
        verify(setOperations).add(eq(expectedHashKey), anyString());
        verify(redisTemplate).hasKey(expectedHashKey);
        verify(redisTemplate).expire(eq(expectedHashKey), eq(7L), any());
        log.info("Test passed: addToHashIndex successfully adds content and sets TTL");
    }

    /**
     * 测试添加哈希索引（键已存在，不设置 TTL）
     */
    @Test
    void testAddToHashIndex_KeyExists_NoTTL() {
        // Given
        String groupId = "test-group-003b";
        String content = "我喜欢运动健身";
        String expectedHashKey = "ai:memory:hash:test-group-003b";

        // Mock: 键已存在
        when(setOperations.add(eq(expectedHashKey), anyString())).thenReturn(1L);
        when(redisTemplate.hasKey(expectedHashKey)).thenReturn(true);

        // When
        redisMemoryIndexService.addToHashIndex(groupId, content);

        // Then
        verify(setOperations).add(eq(expectedHashKey), anyString());
        verify(redisTemplate).hasKey(expectedHashKey);
        // 验证 expire 不被调用（因为键已存在）
        verify(redisTemplate, never()).expire(any(), anyLong(), any());
        log.info("Test passed: addToHashIndex does not set TTL when key exists");
    }

    /**
     * 测试缓存最近记忆成功（键不存在，设置 TTL，不触发 trimming）
     */
    @Test
    void testCacheRecentMemory_Success() {
        // Given
        String groupId = "test-group-004";
        Long memoryId = 12345L;
        String content = "我对花生过敏";
        double score = System.currentTimeMillis();
        String expectedRecentKey = "ai:memory:recent:test-group-004";
        String expectedValue = "12345:我对花生过敏";

        // Mock: Redis 操作成功
        when(zSetOperations.add(expectedRecentKey, expectedValue, score)).thenReturn(true);
        when(zSetOperations.size(expectedRecentKey)).thenReturn(5L);  // 小于 MAX_RECENT_MEMORIES (20)
        // 添加前键不存在，添加后键存在
        when(redisTemplate.hasKey(expectedRecentKey))
                .thenReturn(false)  // 第一次调用返回 false
                .thenReturn(true);  // 后续调用返回 true
        when(redisTemplate.expire(eq(expectedRecentKey), anyLong(), any())).thenReturn(true);

        // When
        redisMemoryIndexService.cacheRecentMemory(groupId, memoryId, content, score);

        // Then
        verify(zSetOperations).add(expectedRecentKey, expectedValue, score);
        verify(zSetOperations).size(expectedRecentKey);
        // 验证 removeRange 不被调用（因为 size < MAX_RECENT_MEMORIES）
        verify(zSetOperations, never()).removeRange(any(), anyLong(), anyLong());
        verify(redisTemplate).hasKey(expectedRecentKey);
        verify(redisTemplate).expire(eq(expectedRecentKey), eq(24L), any());
        log.info("Test passed: cacheRecentMemory successfully caches memory");
    }

    /**
     * 测试缓存最近记忆（键已存在，不设置 TTL）
     */
    @Test
    void testCacheRecentMemory_KeyExists_NoTTL() {
        // Given
        String groupId = "test-group-004b";
        Long memoryId = 12346L;
        String content = "我对花生过敏";
        double score = System.currentTimeMillis();
        String expectedRecentKey = "ai:memory:recent:test-group-004b";
        String expectedValue = "12346:我对花生过敏";

        // Mock: 键已存在
        when(zSetOperations.add(expectedRecentKey, expectedValue, score)).thenReturn(true);
        when(zSetOperations.size(expectedRecentKey)).thenReturn(10L);
        when(redisTemplate.hasKey(expectedRecentKey)).thenReturn(true);

        // When
        redisMemoryIndexService.cacheRecentMemory(groupId, memoryId, content, score);

        // Then
        verify(zSetOperations).add(expectedRecentKey, expectedValue, score);
        verify(zSetOperations).size(expectedRecentKey);
        verify(redisTemplate).hasKey(expectedRecentKey);
        // 验证 expire 不被调用（因为键已存在）
        verify(redisTemplate, never()).expire(any(), anyLong(), any());
        log.info("Test passed: cacheRecentMemory does not set TTL when key exists");
    }

    /**
     * 测试缓存最近记忆（超过最大数量，触发 trimming）
     */
    @Test
    void testCacheRecentMemory_TrimsWhenExceedsMaxSize() {
        // Given
        String groupId = "test-group-004c";
        Long memoryId = 12347L;
        String content = "我对花生过敏";
        double score = System.currentTimeMillis();
        String expectedRecentKey = "ai:memory:recent:test-group-004c";
        String expectedValue = "12347:我对花生过敏";

        // Mock: size 超过 MAX_RECENT_MEMORIES (20)
        when(zSetOperations.add(expectedRecentKey, expectedValue, score)).thenReturn(true);
        when(zSetOperations.size(expectedRecentKey)).thenReturn(25L);  // 超过 20
        when(redisTemplate.hasKey(expectedRecentKey)).thenReturn(true);

        // When
        redisMemoryIndexService.cacheRecentMemory(groupId, memoryId, content, score);

        // Then
        verify(zSetOperations).add(expectedRecentKey, expectedValue, score);
        verify(zSetOperations).size(expectedRecentKey);
        // 验证 removeRange 被调用，删除最旧的 5 条记忆（25 - 20 = 5）
        // removeRange(key, 0, 4) 删除索引 0-4 的元素（最旧的 5 条）
        verify(zSetOperations).removeRange(expectedRecentKey, 0, 4);
        log.info("Test passed: cacheRecentMemory trims oldest memories when exceeding max size");
    }

    /**
     * 测试获取最近记忆（按时间倒序）
     */
    @Test
    void testGetRecentMemories_ReturnsInReverseOrder() {
        // Given
        String groupId = "test-group-005";
        int limit = 5;
        String expectedRecentKey = "ai:memory:recent:test-group-005";

        // Mock: Redis 返回按时间倒序的记忆（最新的在前）
        Set<Object> mockResults = new LinkedHashSet<>();
        mockResults.add("103:最新的记忆");
        mockResults.add("102:第二新的记忆");
        mockResults.add("101:第三新的记忆");

        when(zSetOperations.reverseRange(expectedRecentKey, 0, limit - 1)).thenReturn(mockResults);

        // When
        List<String> results = redisMemoryIndexService.getRecentMemories(groupId, limit);

        // Then
        assertNotNull(results, "Results should not be null");
        assertEquals(3, results.size(), "Should return 3 memories");
        assertEquals("最新的记忆", results.get(0), "First memory should be the newest");
        assertEquals("第二新的记忆", results.get(1), "Second memory should be the second newest");
        assertEquals("第三新的记忆", results.get(2), "Third memory should be the third newest");

        verify(zSetOperations).reverseRange(expectedRecentKey, 0, limit - 1);
        log.info("Test passed: getRecentMemories returns memories in reverse order");
    }

    /**
     * 测试获取最近记忆（限制数量）
     */
    @Test
    void testGetRecentMemories_LimitWorks() {
        // Given
        String groupId = "test-group-006";
        int limit = 2;
        String expectedRecentKey = "ai:memory:recent:test-group-006";

        // Mock: Redis 返回限制数量的记忆
        Set<Object> mockResults = new LinkedHashSet<>();
        mockResults.add("202:记忆A");
        mockResults.add("201:记忆B");

        when(zSetOperations.reverseRange(expectedRecentKey, 0, limit - 1)).thenReturn(mockResults);

        // When
        List<String> results = redisMemoryIndexService.getRecentMemories(groupId, limit);

        // Then
        assertNotNull(results, "Results should not be null");
        assertEquals(2, results.size(), "Should return exactly 2 memories");
        assertEquals("记忆A", results.get(0));
        assertEquals("记忆B", results.get(1));

        verify(zSetOperations).reverseRange(expectedRecentKey, 0, 1);
        log.info("Test passed: getRecentMemories respects limit parameter");
    }

    /**
     * 测试 Redis 失败时的优雅降级
     */
    @Test
    void testRedisFailure_GracefulDegradation() {
        // Given
        String groupId = "test-group-007";
        String content = "测试内容";

        // Mock: Redis 抛出异常
        when(setOperations.isMember(anyString(), anyString()))
                .thenThrow(new RuntimeException("Redis connection failed"));

        // When: isDuplicate 遇到异常
        boolean isDuplicateResult = redisMemoryIndexService.isDuplicate(groupId, content);

        // Then: 应该返回 false（优雅降级）
        assertFalse(isDuplicateResult, "Should return false on Redis failure");
        log.info("Test passed: isDuplicate gracefully degrades on Redis failure");

        // When: addToHashIndex 遇到异常
        when(setOperations.add(anyString(), anyString()))
                .thenThrow(new RuntimeException("Redis connection failed"));

        // Then: 应该不抛出异常（优雅降级）
        assertDoesNotThrow(() -> redisMemoryIndexService.addToHashIndex(groupId, content),
                "addToHashIndex should not throw exception on Redis failure");
        log.info("Test passed: addToHashIndex gracefully degrades on Redis failure");

        // When: cacheRecentMemory 遇到异常
        when(zSetOperations.add(anyString(), anyString(), anyDouble()))
                .thenThrow(new RuntimeException("Redis connection failed"));

        // Then: 应该不抛出异常（优雅降级）
        assertDoesNotThrow(() -> redisMemoryIndexService.cacheRecentMemory(groupId, 1L, content, 1.0),
                "cacheRecentMemory should not throw exception on Redis failure");
        log.info("Test passed: cacheRecentMemory gracefully degrades on Redis failure");

        // When: getRecentMemories 遇到异常
        when(zSetOperations.reverseRange(anyString(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("Redis connection failed"));

        // Then: 应该返回空列表（优雅降级）
        List<String> recentMemories = redisMemoryIndexService.getRecentMemories(groupId, 5);
        assertNotNull(recentMemories, "Should return non-null list on Redis failure");
        assertTrue(recentMemories.isEmpty(), "Should return empty list on Redis failure");
        log.info("Test passed: getRecentMemories gracefully degrades on Redis failure");
    }

    /**
     * 测试 dedupEnabled=false 时的行为
     */
    @Test
    void testDedupDisabled_SkipsRedisOperations() {
        // Given
        String groupId = "test-group-008";
        String content = "测试内容";
        Long memoryId = 99999L;
        double score = System.currentTimeMillis();

        // 设置 dedupEnabled 为 false
        ReflectionTestUtils.setField(redisMemoryIndexService, "dedupEnabled", false);

        // When & Then: isDuplicate 应该返回 false 且不调用 Redis
        boolean isDuplicateResult = redisMemoryIndexService.isDuplicate(groupId, content);
        assertFalse(isDuplicateResult, "Should return false when dedup is disabled");
        verify(setOperations, never()).isMember(anyString(), anyString());
        log.info("Test passed: isDuplicate skips Redis when dedupEnabled=false");

        // When & Then: addToHashIndex 应该不调用 Redis
        redisMemoryIndexService.addToHashIndex(groupId, content);
        verify(setOperations, never()).add(anyString(), anyString());
        verify(redisTemplate, never()).hasKey(anyString());
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any());
        log.info("Test passed: addToHashIndex skips Redis when dedupEnabled=false");

        // When & Then: cacheRecentMemory 应该不调用 Redis
        redisMemoryIndexService.cacheRecentMemory(groupId, memoryId, content, score);
        verify(zSetOperations, never()).add(anyString(), anyString(), anyDouble());
        verify(zSetOperations, never()).size(anyString());
        log.info("Test passed: cacheRecentMemory skips Redis when dedupEnabled=false");
    }

    /**
     * 测试 redisTemplate=null 时的行为
     */
    @Test
    void testRedisTemplateNull_GracefulDegradation() {
        // Given
        String groupId = "test-group-009";
        String content = "测试内容";
        Long memoryId = 88888L;
        double score = System.currentTimeMillis();

        // 设置 redisTemplate 为 null
        ReflectionTestUtils.setField(redisMemoryIndexService, "redisTemplate", null);

        // When & Then: isDuplicate 应该返回 false
        boolean isDuplicateResult = redisMemoryIndexService.isDuplicate(groupId, content);
        assertFalse(isDuplicateResult, "Should return false when redisTemplate is null");
        log.info("Test passed: isDuplicate returns false when redisTemplate is null");

        // When & Then: addToHashIndex 应该不抛出异常
        assertDoesNotThrow(() -> redisMemoryIndexService.addToHashIndex(groupId, content),
                "addToHashIndex should not throw exception when redisTemplate is null");
        log.info("Test passed: addToHashIndex handles null redisTemplate gracefully");

        // When & Then: cacheRecentMemory 应该不抛出异常
        assertDoesNotThrow(() -> redisMemoryIndexService.cacheRecentMemory(groupId, memoryId, content, score),
                "cacheRecentMemory should not throw exception when redisTemplate is null");
        log.info("Test passed: cacheRecentMemory handles null redisTemplate gracefully");

        // When & Then: getRecentMemories 应该返回空列表
        List<String> recentMemories = redisMemoryIndexService.getRecentMemories(groupId, 5);
        assertNotNull(recentMemories, "Should return non-null list when redisTemplate is null");
        assertTrue(recentMemories.isEmpty(), "Should return empty list when redisTemplate is null");
        log.info("Test passed: getRecentMemories returns empty list when redisTemplate is null");
    }
}
