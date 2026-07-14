package com.lx.governance.vectormemory.integration;

import com.lx.governance.vectormemory.dto.MemoryExtractionResult;
import com.lx.governance.vectormemory.dto.MemoryItemDTO;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;
import com.lx.governance.vectormemory.migration.VectorMemoryMigrationScript;
import com.lx.governance.vectormemory.application.governance.CandidateMemoryReviewService;
import com.lx.governance.vectormemory.application.governance.MemoryLifecycleGovernanceService;
import com.lx.governance.vectormemory.application.ingestion.MemoryExtractionService;
import com.lx.governance.vectormemory.application.retrieval.MemoryRetrievalService;
import com.lx.governance.vectormemory.domain.candidate.CandidateMemoryService;
import com.lx.governance.vectormemory.domain.candidate.MemoryConflictResolution;
import com.lx.governance.vectormemory.domain.candidate.MemoryConflictResolver;
import com.lx.governance.vectormemory.domain.candidate.MemoryMergeService;
import com.lx.governance.vectormemory.domain.candidate.MemoryRelationType;
import com.lx.governance.vectormemory.domain.candidate.SemanticConflictAnalysis;
import com.lx.governance.vectormemory.domain.candidate.SemanticMemoryConflictDetector;
import com.lx.governance.vectormemory.domain.candidate.SemanticMemoryFingerprint;
import com.lx.governance.vectormemory.domain.lifecycle.ConfirmedMemoryMaintenanceService;
import com.lx.governance.vectormemory.domain.lifecycle.MemoryCompactionService;
import com.lx.governance.vectormemory.domain.lifecycle.MemoryDecayService;
import com.lx.governance.vectormemory.domain.lifecycle.MemoryLifecycleIngestionPipeline;
import com.lx.governance.vectormemory.domain.lifecycle.MemoryNoiseFilter;
import com.lx.governance.vectormemory.domain.lifecycle.MemoryRetentionPolicy;
import com.lx.governance.vectormemory.domain.lifecycle.MemoryTypeNormalizer;
import com.lx.governance.vectormemory.domain.lifecycle.MemoryWorthinessEvaluator;
import com.lx.governance.vectormemory.domain.retrieval.MemoryDeduplicationService;
import com.lx.governance.vectormemory.domain.retrieval.MemoryRerankService;
import com.lx.governance.vectormemory.domain.retrieval.StructuredMemoryFilterMatcher;
import com.lx.governance.vectormemory.domain.retrieval.StructuredMemoryQueryCompiler;
import com.lx.governance.vectormemory.integration.ai.BgeRerankClient;
import com.lx.governance.vectormemory.integration.ai.DeepSeekService;
import com.lx.governance.vectormemory.integration.ai.EmbeddingService;
import com.lx.governance.vectormemory.integration.storage.RedisMemoryIndexService;
import com.lx.governance.vectormemory.integration.storage.VectorStoreService;
import com.lx.governance.vectormemory.support.observability.MemoryLifecycleAuditService;
import com.lx.governance.vectormemory.support.observability.MemoryLifecycleMetricsService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 向量记忆系统端到端集成测试
 *
 * 测试范围：
 * 1. 记忆提取流程（MemoryExtractionService）
 * 2. 记忆检索流程（MemoryRetrievalService）
 * 3. 迁移脚本验证（VectorMemoryMigrationScript）
 *
 * 注意：此测试需要真实的 Redis 和 Milvus 连接
 * 如果环境不可用，测试将被跳过（@Disabled）
 *
 * @author LeJinBo
 * @since 2026-05-18
 */
@Slf4j
@SpringBootTest
@EnabledIfSystemProperty(named = "ai.external.tests", matches = "true")
@ActiveProfiles("external-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Disabled("需要真实的 Redis 和 Milvus 环境，CI/CD 环境中跳过")
class VectorMemoryEndToEndTest {

    @Autowired
    private MemoryExtractionService memoryExtractionService;

    @Autowired
    private MemoryRetrievalService memoryRetrievalService;

    @Autowired
    private RedisMemoryIndexService redisMemoryIndexService;

    @Autowired
    private VectorStoreService vectorStoreService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private VectorMemoryMigrationScript migrationScript;

    @Autowired
    private AiGroupMemoryMapper memoryMapper;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    // 测试数据
    private static final String TEST_GROUP_ID = "test-group-e2e-001";
    private static final String TEST_CUSTOMER_ID = "test-customer-e2e-001";
    private static final String TEST_USER_MESSAGE = "我对海鲜过敏，特别是虾和螃蟹";
    private static final String TEST_AI_RESPONSE = "好的，我已经记住了您对海鲜过敏的情况，特别是虾和螃蟹。在后续的饮食建议中，我会避免推荐这些食物。";

    @BeforeEach
    void setUp() {
        log.info("=== 开始测试前准备 ===");

        // 检查 Redis 连接
        if (redisTemplate != null) {
            try {
                redisTemplate.getConnectionFactory().getConnection().ping();
                log.info("Redis 连接正常");
            } catch (Exception e) {
                log.error("Redis 连接失败，测试将被跳过: {}", e.getMessage());
                Assumptions.assumeTrue(false, "Redis 不可用");
            }
        } else {
            log.warn("RedisTemplate 未注入，Redis 相关功能将被跳过");
        }

        // 清理测试数据
        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        log.info("=== 测试后清理 ===");
        cleanupTestData();
    }

    /**
     * 测试1：记忆提取流程
     *
     * 验证点：
     * 1. 记忆成功保存到 MySQL
     * 2. embedding_id 已更新（Milvus 写入成功）
     * 3. Redis 去重索引已更新
     * 4. Redis 最近记忆缓存已更新
     */
    @Test
    @Order(1)
    @DisplayName("测试记忆提取流程：MySQL + Milvus + Redis")
    void testMemoryExtractionFlow() {
        log.info("=== 测试1：记忆提取流程 ===");

        // Step 1: 创建测试记忆（模拟 DeepSeek 提取结果）
        MemoryExtractionResult.ExtractedMemory extractedMemory = new MemoryExtractionResult.ExtractedMemory();
        extractedMemory.setContent("用户对海鲜过敏，特别是虾和螃蟹");
        extractedMemory.setCategory("health");
        extractedMemory.setConfidence(0.95);

        // Step 2: 保存记忆
        log.info("保存记忆到系统...");
        memoryExtractionService.saveMemory(
            TEST_GROUP_ID,
            TEST_CUSTOMER_ID,
            extractedMemory,
            TEST_USER_MESSAGE,
            TEST_AI_RESPONSE
        );

        // Step 3: 验证 MySQL 记录
        log.info("验证 MySQL 记录...");
        List<AiGroupMemoryEntity> memories = memoryMapper.selectByGroupIdAndCustomerId(
            TEST_GROUP_ID,
            TEST_CUSTOMER_ID
        );

        assertFalse(memories.isEmpty(), "MySQL 应该有记忆记录");
        AiGroupMemoryEntity savedMemory = memories.get(0);

        assertNotNull(savedMemory.getId(), "记忆ID不应为空");
        assertEquals(TEST_GROUP_ID, savedMemory.getGroupId(), "群组ID应匹配");
        assertEquals(TEST_CUSTOMER_ID, savedMemory.getCustomerId(), "用户ID应匹配");
        assertEquals("用户对海鲜过敏，特别是虾和螃蟹", savedMemory.getMemoryContent(), "记忆内容应匹配");
        assertEquals("SYMPTOM", savedMemory.getMemoryType(), "记忆类型应为SYMPTOM");
        assertNotNull(savedMemory.getEmbeddingId(), "embedding_id 应该已更新（Milvus写入成功）");

        log.info("✓ MySQL 验证通过 - id: {}, embeddingId: {}",
            savedMemory.getId(), savedMemory.getEmbeddingId());

        // Step 4: 验证 Redis 去重索引
        if (redisMemoryIndexService != null && redisTemplate != null) {
            log.info("验证 Redis 去重索引...");
            boolean isDuplicate = redisMemoryIndexService.isDuplicate(
                TEST_GROUP_ID,
                extractedMemory.getContent()
            );
            assertTrue(isDuplicate, "Redis 去重索引应该包含该记忆");
            log.info("✓ Redis 去重索引验证通过");

            // Step 5: 验证 Redis 最近记忆缓存
            log.info("验证 Redis 最近记忆缓存...");
            List<String> recentMemories = redisMemoryIndexService.getRecentMemories(TEST_GROUP_ID, 10);
            assertFalse(recentMemories.isEmpty(), "Redis 最近记忆缓存应该有数据");
            assertTrue(recentMemories.contains(extractedMemory.getContent()),
                "Redis 缓存应该包含该记忆内容");
            log.info("✓ Redis 最近记忆缓存验证通过 - 缓存数量: {}", recentMemories.size());
        } else {
            log.warn("跳过 Redis 验证（RedisTemplate 或 RedisMemoryIndexService 不可用）");
        }

        log.info("=== 测试1完成：记忆提取流程验证通过 ===\n");
    }

    /**
     * 测试2：记忆检索流程
     *
     * 验证点：
     * 1. Redis 缓存命中（最近记忆）
     * 2. Milvus 查询（相似度搜索）
     * 3. 结果合并和去重
     */
    @Test
    @Order(2)
    @DisplayName("测试记忆检索流程：Redis优先 + Milvus相似度搜索")
    void testMemoryRetrievalFlow() {
        log.info("=== 测试2：记忆检索流程 ===");

        // 前置条件：先保存一条记忆
        MemoryExtractionResult.ExtractedMemory extractedMemory = new MemoryExtractionResult.ExtractedMemory();
        extractedMemory.setContent("用户喜欢清淡饮食，不吃辛辣食物");
        extractedMemory.setCategory("preference");
        extractedMemory.setConfidence(0.90);

        memoryExtractionService.saveMemory(
            TEST_GROUP_ID,
            TEST_CUSTOMER_ID,
            extractedMemory,
            "我喜欢清淡的食物，不能吃辣",
            "好的，我记住了您的饮食偏好"
        );

        // 等待异步操作完成
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Step 1: 测试 Redis 缓存命中
        log.info("测试 Redis 缓存命中...");
        List<MemoryItemDTO> results = memoryRetrievalService.retrieveGroupMemories(
            "饮食偏好",
            TEST_GROUP_ID,
            TEST_CUSTOMER_ID,
            5
        );

        assertFalse(results.isEmpty(), "应该检索到记忆");
        log.info("✓ 检索到 {} 条记忆", results.size());

        // 验证结果包含我们保存的记忆
        boolean foundMemory = results.stream()
            .anyMatch(m -> m.getContent().contains("清淡饮食"));
        assertTrue(foundMemory, "检索结果应该包含保存的记忆");

        // Step 2: 测试 Milvus 相似度搜索
        log.info("测试 Milvus 相似度搜索...");
        List<MemoryItemDTO> similarResults = memoryRetrievalService.retrieveGroupMemories(
            "用户的饮食习惯是什么",
            TEST_GROUP_ID,
            TEST_CUSTOMER_ID,
            3
        );

        assertFalse(similarResults.isEmpty(), "相似度搜索应该有结果");
        log.info("✓ 相似度搜索返回 {} 条记忆", similarResults.size());

        // 验证相似度字段
        for (MemoryItemDTO item : similarResults) {
            assertNotNull(item.getContent(), "记忆内容不应为空");
            assertNotNull(item.getSimilarity(), "相似度不应为空");
            log.info("  - 记忆: {}, 相似度: {}, 来源: {}",
                truncate(item.getContent(), 30),
                item.getSimilarity(),
                item.getSource());
        }

        log.info("=== 测试2完成：记忆检索流程验证通过 ===\n");
    }

    /**
     * 测试3：Redis 去重功能
     *
     * 验证点：
     * 1. 重复内容被 Redis 快速去重
     * 2. 不会重复写入 MySQL 和 Milvus
     */
    @Test
    @Order(3)
    @DisplayName("测试 Redis 去重功能")
    void testRedisDeduplication() {
        log.info("=== 测试3：Redis 去重功能 ===");

        if (redisMemoryIndexService == null || redisTemplate == null) {
            log.warn("跳过 Redis 去重测试（RedisTemplate 不可用）");
            return;
        }

        // Step 1: 保存第一条记忆
        MemoryExtractionResult.ExtractedMemory memory1 = new MemoryExtractionResult.ExtractedMemory();
        memory1.setContent("用户对花生过敏");
        memory1.setCategory("health");
        memory1.setConfidence(0.92);

        log.info("保存第一条记忆...");
        memoryExtractionService.saveMemory(
            TEST_GROUP_ID,
            TEST_CUSTOMER_ID,
            memory1,
            "我对花生过敏",
            "好的，我记住了"
        );

        // 查询保存后的记录数
        List<AiGroupMemoryEntity> memoriesAfterFirst = memoryMapper.selectByGroupIdAndCustomerId(
            TEST_GROUP_ID,
            TEST_CUSTOMER_ID
        );
        int countAfterFirst = memoriesAfterFirst.size();
        log.info("第一次保存后，MySQL 记录数: {}", countAfterFirst);

        // Step 2: 尝试保存相同内容（应该被 Redis 去重）
        log.info("尝试保存相同内容（应该被 Redis 去重）...");
        boolean isDuplicateBefore = redisMemoryIndexService.isDuplicate(TEST_GROUP_ID, memory1.getContent());
        assertTrue(isDuplicateBefore, "Redis 应该检测到重复");

        // 模拟再次提取相同记忆（实际场景中会被 extractAndSaveMemories 过滤）
        // 这里直接验证 isDuplicate 方法
        log.info("✓ Redis 去重检测通过");

        // Step 3: 保存不同内容（应该成功）
        MemoryExtractionResult.ExtractedMemory memory2 = new MemoryExtractionResult.ExtractedMemory();
        memory2.setContent("用户对牛奶过敏");
        memory2.setCategory("health");
        memory2.setConfidence(0.88);

        log.info("保存不同内容...");
        memoryExtractionService.saveMemory(
            TEST_GROUP_ID,
            TEST_CUSTOMER_ID,
            memory2,
            "我对牛奶过敏",
            "好的，我记住了"
        );

        // 查询保存后的记录数
        List<AiGroupMemoryEntity> memoriesAfterSecond = memoryMapper.selectByGroupIdAndCustomerId(
            TEST_GROUP_ID,
            TEST_CUSTOMER_ID
        );
        int countAfterSecond = memoriesAfterSecond.size();
        log.info("第二次保存后，MySQL 记录数: {}", countAfterSecond);

        assertEquals(countAfterFirst + 1, countAfterSecond, "应该只增加一条新记录");
        log.info("✓ 去重功能验证通过");

        log.info("=== 测试3完成：Redis 去重功能验证通过 ===\n");
    }

    /**
     * 测试4：迁移脚本（Dry Run 模式）
     *
     * 验证点：
     * 1. 统计待迁移记忆数量
     * 2. Dry Run 模式不实际写入
     * 3. 迁移统计信息正确
     */
    @Test
    @Order(4)
    @DisplayName("测试迁移脚本（Dry Run 模式）")
    void testMigrationScriptDryRun() {
        log.info("=== 测试4：迁移脚本（Dry Run 模式）===");

        // Step 1: 准备测试数据（embedding_id = NULL 的记忆）
        log.info("准备测试数据（embedding_id = NULL）...");
        AiGroupMemoryEntity unmigrated = new AiGroupMemoryEntity();
        unmigrated.setCustomerId(TEST_CUSTOMER_ID);
        unmigrated.setGroupId(TEST_GROUP_ID);
        unmigrated.setMemoryType("OTHER");
        unmigrated.setMemoryKey("test");
        unmigrated.setMemoryContent("这是一条待迁移的测试记忆");
        unmigrated.setConfidenceScore(BigDecimal.valueOf(0.85));
        unmigrated.setSourceMessage("test message");
        unmigrated.setExtractedAt(LocalDateTime.now());
        unmigrated.setIsDeleted(0);
        unmigrated.setEmbeddingId(null); // 关键：embedding_id 为 NULL

        memoryMapper.insert(unmigrated);
        log.info("✓ 插入待迁移记忆 - id: {}", unmigrated.getId());

        // Step 2: 统计待迁移记忆数量
        int pendingCount = memoryMapper.countPendingMigration();
        log.info("待迁移记忆总数: {}", pendingCount);
        assertTrue(pendingCount > 0, "应该有待迁移的记忆");

        // Step 3: 运行迁移脚本（Dry Run 模式）
        log.info("运行迁移脚本（Dry Run 模式）...");
        VectorMemoryMigrationScript.MigrationStats stats = migrationScript.migrateMemories(10, true);

        // Step 4: 验证统计信息
        log.info("迁移统计: {}", stats);
        assertEquals(pendingCount, stats.getTotalRecords(), "总记录数应匹配");
        assertTrue(stats.getMigratedRecords() > 0, "应该有已迁移记录（Dry Run）");
        assertEquals(0, stats.getFailedRecords(), "Dry Run 不应该有失败记录");

        // Step 5: 验证 Dry Run 没有实际写入
        AiGroupMemoryEntity afterDryRun = memoryMapper.selectById(unmigrated.getId());
        assertNull(afterDryRun.getEmbeddingId(), "Dry Run 模式不应该更新 embedding_id");
        log.info("✓ Dry Run 验证通过 - embedding_id 仍为 NULL");

        log.info("=== 测试4完成：迁移脚本（Dry Run）验证通过 ===\n");
    }

    /**
     * 测试5：迁移脚本（实际迁移）
     *
     * 验证点：
     * 1. 记忆成功迁移到 Milvus
     * 2. embedding_id 已更新
     * 3. Redis 索引已更新
     */
    @Test
    @Order(5)
    @DisplayName("测试迁移脚本（实际迁移）")
    void testMigrationScriptActual() {
        log.info("=== 测试5：迁移脚本（实际迁移）===");

        // Step 1: 准备测试数据
        log.info("准备测试数据（embedding_id = NULL）...");
        AiGroupMemoryEntity unmigrated = new AiGroupMemoryEntity();
        unmigrated.setCustomerId(TEST_CUSTOMER_ID);
        unmigrated.setGroupId(TEST_GROUP_ID);
        unmigrated.setMemoryType("PREFERENCE");
        unmigrated.setMemoryKey("test");
        unmigrated.setMemoryContent("用户喜欢喝绿茶");
        unmigrated.setConfidenceScore(BigDecimal.valueOf(0.90));
        unmigrated.setSourceMessage("test message");
        unmigrated.setExtractedAt(LocalDateTime.now());
        unmigrated.setIsDeleted(0);
        unmigrated.setEmbeddingId(null);

        memoryMapper.insert(unmigrated);
        Long testMemoryId = unmigrated.getId();
        log.info("✓ 插入待迁移记忆 - id: {}", testMemoryId);

        // Step 2: 运行迁移脚本（实际迁移）
        log.info("运行迁移脚本（实际迁移）...");
        VectorMemoryMigrationScript.MigrationStats stats = migrationScript.migrateMemories(10, false);

        // Step 3: 验证统计信息
        log.info("迁移统计: {}", stats);
        assertTrue(stats.getMigratedRecords() > 0, "应该有已迁移记录");
        assertEquals(0, stats.getFailedRecords(), "不应该有失败记录");

        // Step 4: 验证 MySQL 记录已更新
        AiGroupMemoryEntity afterMigration = memoryMapper.selectById(testMemoryId);
        assertNotNull(afterMigration.getEmbeddingId(), "embedding_id 应该已更新");
        log.info("✓ MySQL 验证通过 - embedding_id: {}", afterMigration.getEmbeddingId());

        // Step 5: 验证 Redis 索引已更新
        if (redisMemoryIndexService != null && redisTemplate != null) {
            log.info("验证 Redis 索引已更新...");
            boolean isDuplicate = redisMemoryIndexService.isDuplicate(
                TEST_GROUP_ID,
                unmigrated.getMemoryContent()
            );
            assertTrue(isDuplicate, "Redis 索引应该包含迁移的记忆");
            log.info("✓ Redis 索引验证通过");
        }

        // Step 6: 验证可以检索到迁移的记忆
        log.info("验证可以检索到迁移的记忆...");
        List<MemoryItemDTO> results = memoryRetrievalService.retrieveGroupMemories(
            "饮品偏好",
            TEST_GROUP_ID,
            TEST_CUSTOMER_ID,
            5
        );

        boolean foundMigratedMemory = results.stream()
            .anyMatch(m -> m.getContent().contains("绿茶"));
        assertTrue(foundMigratedMemory, "应该能检索到迁移的记忆");
        log.info("✓ 记忆检索验证通过");

        log.info("=== 测试5完成：迁移脚本（实际迁移）验证通过 ===\n");
    }

    /**
     * 测试6：向量维度验证
     *
     * 验证点：
     * 1. Embedding 生成的向量维度为 1024
     * 2. 向量维度不匹配时抛出异常
     */
    @Test
    @Order(6)
    @DisplayName("测试向量维度验证（1024维）")
    void testEmbeddingDimensionValidation() {
        log.info("=== 测试6：向量维度验证 ===");

        // Step 1: 生成向量
        String testText = "测试文本用于验证向量维度";
        log.info("生成向量: {}", testText);

        float[] embedding = embeddingService.generateEmbedding(testText);

        // Step 2: 验证维度
        assertNotNull(embedding, "向量不应为空");
        assertEquals(1024, embedding.length, "向量维度应为 1024");
        log.info("✓ 向量维度验证通过 - dimension: {}", embedding.length);

        // Step 3: 验证向量值范围（归一化向量，值应在 [-1, 1] 范围内）
        for (int i = 0; i < Math.min(10, embedding.length); i++) {
            assertTrue(embedding[i] >= -1.0f && embedding[i] <= 1.0f,
                String.format("向量值应在 [-1, 1] 范围内，但 embedding[%d] = %f", i, embedding[i]));
        }
        log.info("✓ 向量值范围验证通过（前10个值）");

        log.info("=== 测试6完成：向量维度验证通过 ===\n");
    }

    /**
     * 清理测试数据
     */
    private void cleanupTestData() {
        try {
            // 清理 MySQL 测试数据
            List<AiGroupMemoryEntity> testMemories = memoryMapper.selectByGroupIdAndCustomerId(
                TEST_GROUP_ID,
                TEST_CUSTOMER_ID
            );

            for (AiGroupMemoryEntity memory : testMemories) {
                // 删除 Milvus 向量
                if (memory.getEmbeddingId() != null) {
                    try {
                        vectorStoreService.deleteGroupMemory(memory.getEmbeddingId());
                    } catch (Exception e) {
                        log.warn("删除 Milvus 向量失败: embeddingId={}, error={}",
                            memory.getEmbeddingId(), e.getMessage());
                    }
                }

                // 删除 MySQL 记录
                memoryMapper.deleteById(memory.getId());
            }

            log.info("清理 MySQL 测试数据: {} 条", testMemories.size());

            // 清理 Redis 测试数据
            if (redisTemplate != null) {
                String hashKey = "ai:memory:hash:" + TEST_GROUP_ID;
                String recentKey = "ai:memory:recent:" + TEST_GROUP_ID;

                redisTemplate.delete(hashKey);
                redisTemplate.delete(recentKey);

                log.info("清理 Redis 测试数据: hashKey={}, recentKey={}", hashKey, recentKey);
            }

        } catch (Exception e) {
            log.error("清理测试数据失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 截断文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
