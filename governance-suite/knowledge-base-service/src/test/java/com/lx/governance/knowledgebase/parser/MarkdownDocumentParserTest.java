package com.lx.governance.knowledgebase.parser;

import com.lx.governance.knowledgebase.domain.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Markdown 解析器测试
 */
public class MarkdownDocumentParserTest {

    private MarkdownDocumentParser createParser() {
        return new MarkdownDocumentParser();
    }

    @Test
    public void testSupports() {
        MarkdownDocumentParser parser = createParser();
        assertTrue(parser.supports("md"));
        assertTrue(parser.supports("markdown"));
        assertTrue(parser.supports("MD"));
        assertTrue(parser.supports("MARKDOWN"));
        assertFalse(parser.supports("pdf"));
        assertFalse(parser.supports("txt"));
    }

    @Test
    public void testParseMarkdown() throws IOException {
        MarkdownDocumentParser parser = createParser();
        // 创建测试 Markdown 文件
        String content = """
                # 测试标题

                这是第一段内容。

                ## 二级标题

                这是第二段内容，包含一些详细信息。

                ### 三级标题

                - 列表项1
                - 列表项2

                这是最后一段内容。

                ```java
                public class Test {
                    public static void main(String[] args) {
                        System.out.println("Hello");
                    }
                }
                ```
                """;

        Path tempFile = Files.createTempFile("test", ".md");
        Files.writeString(tempFile, content);

        try {
            ParsedDocument doc = parser.parse(tempFile.toFile(), "test_doc_001");

            assertNotNull(doc);
            assertEquals("test_doc_001", doc.getDocId());
            assertEquals("markdown", doc.getFileType());
            assertNotNull(doc.getParagraphs());
            assertFalse(doc.getParagraphs().isEmpty());

            System.out.println("解析出的段落数: " + doc.getParagraphs().size());

            // 打印所有段落
            doc.getParagraphs().forEach(para -> {
                System.out.println("段落: " + para.getContent().substring(0, Math.min(50, para.getContent().length())));
                System.out.println("元数据: " + para.getMetadata());
                System.out.println("---");
            });

            // 验证元数据
            assertTrue(doc.getParagraphs().stream()
                    .anyMatch(p -> p.getMetadata().containsKey("heading")),
                    "应该包含标题信息");

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testParseMarkdownWithCodeBlock() throws IOException {
        MarkdownDocumentParser parser = createParser();
        String content = """
                # 代码示例

                这是一段说明文字。

                ```python
                def hello():
                    print("Hello, World!")
                ```

                这是代码后的文字。
                """;

        Path tempFile = Files.createTempFile("test_code", ".md");
        Files.writeString(tempFile, content);

        try {
            ParsedDocument doc = parser.parse(tempFile.toFile(), "test_doc_002");

            assertNotNull(doc);
            assertFalse(doc.getParagraphs().isEmpty());

            // 应该解析出代码块
            boolean hasCodeBlock = doc.getParagraphs().stream()
                    .anyMatch(p -> p.getMetadata().getOrDefault("type", "").equals("code"));

            assertTrue(hasCodeBlock, "应该包含代码块");

            System.out.println("包含代码块的文档，段落数: " + doc.getParagraphs().size());

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testParseEmptyMarkdown() throws IOException {
        MarkdownDocumentParser parser = createParser();
        String content = "";

        Path tempFile = Files.createTempFile("test_empty", ".md");
        Files.writeString(tempFile, content);

        try {
            ParsedDocument doc = parser.parse(tempFile.toFile(), "test_doc_003");

            assertNotNull(doc);
            assertTrue(doc.getParagraphs().isEmpty(), "空文档不应该有段落");

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
