package com.lx.governance.knowledgebase.parser;

import com.lx.governance.knowledgebase.domain.Paragraph;
import com.lx.governance.knowledgebase.domain.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * PDF 文档解析器
 */
@Slf4j
@Component
public class PdfDocumentParser implements DocumentParser {

    @Override
    public ParsedDocument parse(File file, String docId) throws IOException {
        log.info("开始解析 PDF 文档: {}", file.getName());

        try (PDDocument document = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<Paragraph> paragraphs = new ArrayList<>();

            int totalPages = document.getNumberOfPages();
            log.debug("PDF 总页数: {}", totalPages);

            // 逐页提取文本
            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                String pageText = stripper.getText(document);

                if (pageText != null && !pageText.trim().isEmpty()) {
                    // 按段落分割 (双换行符)
                    String[] pageParagraphs = pageText.split("\n\n");

                    for (String para : pageParagraphs) {
                        String trimmedPara = para.trim();
                        if (!trimmedPara.isEmpty()) {
                            Map<String, Object> metadata = new HashMap<>();
                            metadata.put("page", pageNum);
                            metadata.put("source", "pdf");

                            paragraphs.add(Paragraph.builder()
                                    .content(trimmedPara)
                                    .metadata(metadata)
                                    .build());
                        }
                    }
                }
            }

            log.info("PDF 解析完成，提取段落数: {}", paragraphs.size());

            return ParsedDocument.builder()
                    .docId(docId)
                    .fileName(file.getName())
                    .fileType("pdf")
                    .paragraphs(paragraphs)
                    .build();
        }
    }

    @Override
    public boolean supports(String fileType) {
        return "pdf".equalsIgnoreCase(fileType);
    }
}
