package com.lx.governance.knowledgebase.service;

import com.lx.governance.knowledgebase.entity.KnowledgeBase;
import com.lx.governance.knowledgebase.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 知识库服务测试
 */
@SpringBootTest
@Transactional  // 测试后回滚
public class KnowledgeBaseServiceTest {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @MockBean
    private VectorStoreService vectorStoreService;

    @Test
    public void testCreateKnowledgeBase() {
        // 创建知识库
        KnowledgeBase kb = knowledgeBaseService.createKnowledgeBase(
                "测试知识库",
                "这是一个测试知识库",
                500,
                50,
                0.7,
                "test_user"
        );

        assertNotNull(kb);
        assertNotNull(kb.getKbId());
        assertTrue(kb.getKbId().startsWith("kb_"));
        assertEquals("测试知识库", kb.getName());
        assertEquals("这是一个测试知识库", kb.getDescription());
        assertEquals(500, kb.getChunkSize());
        assertEquals(50, kb.getChunkOverlap());
        assertEquals(0.7, kb.getSemanticThreshold());
        assertEquals(1024, kb.getEmbeddingDimension());
        assertEquals("ACTIVE", kb.getStatus());
        assertEquals(0, kb.getDocumentCount());
        assertEquals(0, kb.getChunkCount());
        assertEquals("test_user", kb.getCreatedBy());

        System.out.println("创建的知识库ID: " + kb.getKbId());
        System.out.println("Milvus集合名: " + kb.getMilvusCollection());
    }

    @Test
    public void testGetByKbId() {
        // 先创建
        KnowledgeBase created = knowledgeBaseService.createKnowledgeBase(
                "测试查询",
                "测试查询功能",
                500,
                50,
                0.7,
                "test"
        );

        // 再查询
        KnowledgeBase retrieved = knowledgeBaseService.getByKbId(created.getKbId());

        assertNotNull(retrieved);
        assertEquals(created.getKbId(), retrieved.getKbId());
        assertEquals(created.getName(), retrieved.getName());
    }

    @Test
    public void testIncrementDocumentCount() {
        // 创建知识库
        KnowledgeBase kb = knowledgeBaseService.createKnowledgeBase(
                "测试计数",
                "测试文档计数",
                500,
                50,
                0.7,
                "test"
        );

        assertEquals(0, kb.getDocumentCount());

        // 增加计数
        knowledgeBaseService.incrementDocumentCount(kb.getKbId());

        // 验证
        KnowledgeBase updated = knowledgeBaseService.getByKbId(kb.getKbId());
        assertEquals(1, updated.getDocumentCount());
    }

    @Test
    public void testUpdateChunkCount() {
        // 创建知识库
        KnowledgeBase kb = knowledgeBaseService.createKnowledgeBase(
                "测试切片计数",
                "测试切片计数功能",
                500,
                50,
                0.7,
                "test"
        );

        assertEquals(0, kb.getChunkCount());

        // 更新切片计数
        knowledgeBaseService.updateChunkCount(kb.getKbId(), 10);

        // 验证
        KnowledgeBase updated = knowledgeBaseService.getByKbId(kb.getKbId());
        assertEquals(10, updated.getChunkCount());

        // 再次更新
        knowledgeBaseService.updateChunkCount(kb.getKbId(), 5);
        updated = knowledgeBaseService.getByKbId(kb.getKbId());
        assertEquals(15, updated.getChunkCount());
    }

    @Test
    public void testDeleteKnowledgeBase() {
        // 创建知识库
        KnowledgeBase kb = knowledgeBaseService.createKnowledgeBase(
                "测试删除",
                "测试删除功能",
                500,
                50,
                0.7,
                "test"
        );

        assertNotNull(knowledgeBaseService.getByKbId(kb.getKbId()));

        // 删除
        knowledgeBaseService.deleteKnowledgeBase(kb.getKbId());

        // 验证（软删除，状态变为DELETED）
        KnowledgeBase deleted = knowledgeBaseService.getByKbId(kb.getKbId());
        assertNull(deleted, "软删除后查询应该返回null");
    }

    @Test
    public void testCreateWithDefaultValues() {
        // 使用null值创建，应该使用默认值
        KnowledgeBase kb = knowledgeBaseService.createKnowledgeBase(
                "默认参数测试",
                "测试默认参数",
                null,  // 使用默认值
                null,
                null,
                "test"
        );

        assertNotNull(kb);
        assertEquals(500, kb.getChunkSize(), "应该使用默认切片大小");
        assertEquals(50, kb.getChunkOverlap(), "应该使用默认重叠");
        assertEquals(0.7, kb.getSemanticThreshold(), "应该使用默认阈值");
    }
}
