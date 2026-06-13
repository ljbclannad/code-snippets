package com.lx.governance.knowledgebase.parser;

import com.lx.governance.knowledgebase.domain.Paragraph;
import com.lx.governance.knowledgebase.domain.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class TextDocumentParser implements DocumentParser {

    @Override
    public ParsedDocument parse(File file, String docId) throws IOException {
        log.info("开始解析文本文件: {}", file.getName());

        String content = Files.readString(file.toPath());
        String[] blocks = content.split("(\\R){2,}");
        List<Paragraph> paragraphs = new ArrayList<>();

        for (String block : blocks) {
            String text = block.trim();
            if (text.isEmpty()) {
                continue;
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", "text");
            paragraphs.add(Paragraph.builder()
                    .content(text)
                    .metadata(metadata)
                    .build());
        }

        return ParsedDocument.builder()
                .docId(docId)
                .fileName(file.getName())
                .fileType("txt")
                .paragraphs(paragraphs)
                .build();
    }

    @Override
    public boolean supports(String fileType) {
        return "txt".equalsIgnoreCase(fileType);
    }
}
