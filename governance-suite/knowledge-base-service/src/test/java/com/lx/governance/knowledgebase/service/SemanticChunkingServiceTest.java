package com.lx.governance.knowledgebase.service;

import com.lx.governance.knowledgebase.domain.DocumentChunk;
import com.lx.governance.knowledgebase.domain.Paragraph;
import com.lx.governance.knowledgebase.domain.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 语义切片服务测试
 */
public class SemanticChunkingServiceTest {

    private SemanticChunkingService createChunkingService() {
        return new SemanticChunkingService();
    }

    private ParsedDocument createTestDocument() {
        List<Paragraph> paragraphs = new ArrayList<>();

        // 添加多个段落
        paragraphs.add(createParagraph("高血压是一种常见的慢性疾病，需要长期管理和治疗。", 1));
        paragraphs.add(createParagraph("高血压患者可能出现头痛、头晕、心悸、疲劳等症状。", 1));
        paragraphs.add(createParagraph("常用的降压药物包括ACEI、ARB、钙通道阻滞剂、利尿剂。", 2));
        paragraphs.add(createParagraph("生活方式改善包括低盐饮食、适量运动、戒烟限酒、保持良好心态。", 2));
        paragraphs.add(createParagraph("高血压患者应定期监测血压，遵医嘱服药，不可自行停药。", 3));

        return ParsedDocument.builder()
                .docId("test_doc_001")
                .fileName("test.md")
                .fileType("markdown")
                .paragraphs(paragraphs)
                .build();
    }

    private Paragraph createParagraph(String content, int page) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("page", page);
        metadata.put("source", "test");

        return Paragraph.builder()
                .content(content)
                .metadata(metadata)
                .build();
    }

    @Test
    public void testChunkDocument() {
        SemanticChunkingService chunkingService = createChunkingService();
        ParsedDocument testDocument = createTestDocument();
        // 测试基本切片功能
        List<DocumentChunk> chunks = chunkingService.chunkDocument(
                testDocument,
                200,  // 小的切片大小以便测试
                20,   // 重叠
                0.7   // 阈值
        );

        // 验证结果
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty(), "应该生成切片");

        System.out.println("生成的切片数: " + chunks.size());

        // 验证每个切片
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);

            assertNotNull(chunk.getChunkId(), "切片ID不能为空");
            assertEquals("test_doc_001_" + i, chunk.getChunkId(), "切片ID应该可预测，便于删除");
            assertEquals("test_doc_001", chunk.getDocId(), "文档ID应该匹配");
            assertEquals(i, chunk.getChunkIndex(), "切片索引应该正确");
            assertNotNull(chunk.getContent(), "切片内容不能为空");
            assertNotNull(chunk.getMetadata(), "元数据不能为空");

            System.out.println("切片 " + i + ": 长度=" + chunk.getContent().length() +
                             ", 页码=" + chunk.getMetadata().get("page"));
        }
    }

    @Test
    public void testChunkDocument_EmptyParagraphs() {
        SemanticChunkingService chunkingService = createChunkingService();
        // 测试空文档
        ParsedDocument emptyDoc = ParsedDocument.builder()
                .docId("empty_doc")
                .fileName("empty.md")
                .fileType("markdown")
                .paragraphs(new ArrayList<>())
                .build();

        List<DocumentChunk> chunks = chunkingService.chunkDocument(emptyDoc, 500, 50, 0.7);

        assertNotNull(chunks);
        assertTrue(chunks.isEmpty(), "空文档不应该生成切片");
    }

    @Test
    public void testChunkDocument_WithDefaultParams() {
        SemanticChunkingService chunkingService = createChunkingService();
        ParsedDocument testDocument = createTestDocument();
        // 测试使用默认参数
        List<DocumentChunk> chunks = chunkingService.chunkDocument(
                testDocument,
                null,  // 使用默认值
                null,
                null
        );

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());

        System.out.println("使用默认参数生成的切片数: " + chunks.size());
    }

    @Test
    public void testChunkDocument_LargeChunkSize() {
        SemanticChunkingService chunkingService = createChunkingService();
        ParsedDocument testDocument = createTestDocument();
        // 测试大的切片大小
        List<DocumentChunk> chunks = chunkingService.chunkDocument(
                testDocument,
                2000,  // 很大的切片
                50,
                0.7
        );

        assertNotNull(chunks);
        // 大的切片大小应该生成较少的切片
        System.out.println("大切片大小生成的切片数: " + chunks.size());
    }

    @Test
    public void testMetadataMerging() {
        SemanticChunkingService chunkingService = createChunkingService();
        // 测试跨页的段落合并
        List<Paragraph> multiPageParagraphs = new ArrayList<>();
        multiPageParagraphs.add(createParagraph("第一页的内容" + "A".repeat(100), 1));
        multiPageParagraphs.add(createParagraph("第二页的内容" + "B".repeat(100), 2));
        multiPageParagraphs.add(createParagraph("第三页的内容" + "C".repeat(100), 3));

        ParsedDocument doc = ParsedDocument.builder()
                .docId("multipage_doc")
                .fileName("multipage.md")
                .fileType("markdown")
                .paragraphs(multiPageParagraphs)
                .build();

        List<DocumentChunk> chunks = chunkingService.chunkDocument(doc, 500, 50, 0.7);

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());

        // 检查元数据
        for (DocumentChunk chunk : chunks) {
            assertTrue(chunk.getMetadata().containsKey("page"), "应该包含页码信息");
            System.out.println("切片 " + chunk.getChunkIndex() + " 的页码: " +
                             chunk.getMetadata().get("page"));
        }
    }
}
