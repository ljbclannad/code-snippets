package com.lx.governance.knowledgebase.parser;

import com.lx.governance.knowledgebase.domain.Paragraph;
import com.lx.governance.knowledgebase.domain.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Markdown 文档解析器
 */
@Slf4j
@Component
public class MarkdownDocumentParser implements DocumentParser {

    private final Parser parser = Parser.builder().build();

    @Override
    public ParsedDocument parse(File file, String docId) throws IOException {
        log.info("开始解析 Markdown 文档: {}", file.getName());

        String content = Files.readString(file.toPath());
        Node document = parser.parse(content);

        List<Paragraph> paragraphs = new ArrayList<>();
        extractParagraphs(document, paragraphs, new ArrayDeque<>());

        log.info("Markdown 解析完成，提取段落数: {}", paragraphs.size());

        return ParsedDocument.builder()
                .docId(docId)
                .fileName(file.getName())
                .fileType("markdown")
                .paragraphs(paragraphs)
                .build();
    }

    /**
     * 递归提取段落，保留标题层级
     */
    private void extractParagraphs(Node node, List<Paragraph> paragraphs, Deque<String> headingStack) {
        if (node instanceof Heading heading) {
            // 更新标题栈
            int level = heading.getLevel();
            while (headingStack.size() >= level) {
                headingStack.pollLast();
            }
            headingStack.addLast(getTextContent(heading));
        } else if (node instanceof org.commonmark.node.Paragraph para) {
            String text = getTextContent(para).trim();
            if (!text.isEmpty()) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source", "markdown");
                if (!headingStack.isEmpty()) {
                    metadata.put("heading", String.join(" > ", headingStack));
                }

                paragraphs.add(Paragraph.builder()
                        .content(text)
                        .metadata(metadata)
                        .build());
            }
        } else if (node instanceof Code || node instanceof FencedCodeBlock) {
            // 代码块也作为段落
            String code = getTextContent(node).trim();
            if (!code.isEmpty()) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source", "markdown");
                metadata.put("type", "code");
                if (!headingStack.isEmpty()) {
                    metadata.put("heading", String.join(" > ", headingStack));
                }

                paragraphs.add(Paragraph.builder()
                        .content(code)
                        .metadata(metadata)
                        .build());
            }
        }

        // 递归处理子节点
        Node child = node.getFirstChild();
        while (child != null) {
            extractParagraphs(child, paragraphs, headingStack);
            child = child.getNext();
        }
    }

    /**
     * 提取节点的文本内容
     */
    private String getTextContent(Node node) {
        StringBuilder sb = new StringBuilder();
        extractText(node, sb);
        return sb.toString();
    }

    private void extractText(Node node, StringBuilder sb) {
        if (node instanceof Text text) {
            sb.append(text.getLiteral());
        } else if (node instanceof Code code) {
            sb.append(code.getLiteral());
        } else if (node instanceof FencedCodeBlock codeBlock) {
            sb.append(codeBlock.getLiteral());
        }

        Node child = node.getFirstChild();
        while (child != null) {
            extractText(child, sb);
            child = child.getNext();
        }
    }

    @Override
    public boolean supports(String fileType) {
        return "md".equalsIgnoreCase(fileType) || "markdown".equalsIgnoreCase(fileType);
    }
}
