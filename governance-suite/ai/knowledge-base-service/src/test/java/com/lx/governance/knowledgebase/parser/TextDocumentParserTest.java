package com.lx.governance.knowledgebase.parser;

import com.lx.governance.knowledgebase.domain.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TextDocumentParserTest {

    private TextDocumentParser createParser() {
        return new TextDocumentParser();
    }

    @Test
    void testSupportsTxt() {
        TextDocumentParser parser = createParser();
        assertTrue(parser.supports("txt"));
        assertTrue(parser.supports("TXT"));
        assertFalse(parser.supports("md"));
    }

    @Test
    void testParseTextDocument() throws IOException {
        TextDocumentParser parser = createParser();
        String content = """
                第一段内容。

                第二段内容。
                仍然属于第二段。

                第三段内容。
                """;

        Path tempFile = Files.createTempFile("test", ".txt");
        Files.writeString(tempFile, content);

        try {
            ParsedDocument document = parser.parse(tempFile.toFile(), "doc_txt_001");

            assertNotNull(document);
            assertEquals("doc_txt_001", document.getDocId());
            assertEquals("txt", document.getFileType());
            assertEquals(3, document.getParagraphs().size());
            assertEquals("text", document.getParagraphs().get(0).getMetadata().get("source"));
            assertTrue(document.getParagraphs().get(1).getContent().contains("仍然属于第二段"));
        }
        finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
