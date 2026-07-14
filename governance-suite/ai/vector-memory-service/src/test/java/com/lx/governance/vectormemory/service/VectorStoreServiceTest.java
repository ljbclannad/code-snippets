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

import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VectorStoreService 单元测试
 * 测试向量存储和检索功能
 *
 * 注意：此测试需要 Milvus 服务运行，且需要配置 BatchingStrategy
 * 当前由于配置问题暂时禁用，待完善配置后启用
 *
 * @author LeJinBo
 * @since 2026-05-13
 */
@Slf4j
@SpringBootTest
@EnabledIfSystemProperty(named = "ai.external.tests", matches = "true")
@ActiveProfiles("external-test")
class VectorStoreServiceTest {

    @Autowired
    private VectorStoreService vectorStoreService;

    /**
     * 测试保存和检索群组记忆
     *
     * 注意：此测试需要以下条件：
     * 1. Milvus 服务运行在 localhost:19530
     * 2. DashScope API Key 配置正确
     * 3. MilvusVectorStore 配置了有效的 BatchingStrategy
     */
    @Test
    @Disabled("Requires Milvus service and proper BatchingStrategy configuration")
    void testSaveAndSearchGroupMemory() throws InterruptedException {
        // 创建测试记忆
        AiGroupMemoryEntity memory = new AiGroupMemoryEntity();
        memory.setId(1L);
        memory.setCustomerId("test-customer-001");
        memory.setGroupId("test-group-001");
        memory.setMemoryType("preference");
        memory.setMemoryKey("allergy");
        memory.setMemoryContent("我对海鲜过敏");
        memory.setConfidenceScore(new BigDecimal("0.95"));
        memory.setExtractedAt(LocalDateTime.now());

        // 保存到 Milvus
        String embeddingId = vectorStoreService.saveGroupMemory(memory);
        log.info("Saved group memory with embeddingId: {}", embeddingId);

        // 验证：embeddingId 不为 null
        assertNotNull(embeddingId, "EmbeddingId should not be null");

        // 等待 2 秒（索引时间）
        log.info("Waiting 2 seconds for indexing...");
        Thread.sleep(2000);

        // 检索记忆（查询："过敏信息"）
        String query = "过敏信息";
        List<Document> results = vectorStoreService.searchGroupMemories(
                query,
                "test-group-001",
                "test-customer-001",
                5
        );

        // 验证：结果不为 null
        assertNotNull(results, "Search results should not be null");

        // 打印检索结果
        log.info("Search query: '{}'", query);
        log.info("Found {} results:", results.size());
        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            log.info("Result {}: content='{}', metadata={}, score={}",
                    i + 1,
                    doc.getContent(),
                    doc.getMetadata(),
                    doc.getMetadata().get("distance"));
        }

        // 如果找到结果，验证内容包含预期的记忆
        if (!results.isEmpty()) {
            boolean foundExpectedMemory = results.stream()
                    .anyMatch(doc -> doc.getContent().contains("海鲜过敏"));
            assertTrue(foundExpectedMemory, "Should find the saved memory about seafood allergy");
        }
    }
}
