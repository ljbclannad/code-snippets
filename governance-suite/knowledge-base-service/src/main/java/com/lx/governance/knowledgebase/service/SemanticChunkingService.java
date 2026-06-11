package com.lx.governance.knowledgebase.service;

import com.lx.governance.knowledgebase.domain.DocumentChunk;
import com.lx.governance.knowledgebase.domain.Paragraph;
import com.lx.governance.knowledgebase.domain.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 语义切片服务
 * 负责将文档段落按照语义相似度进行智能合并切片
 */
@Slf4j
@Service
public class SemanticChunkingService {

    @Value("${knowledge-base.chunking.default-size:500}")
    private int defaultChunkSize;

    @Value("${knowledge-base.chunking.default-overlap:50}")
    private int defaultChunkOverlap;

    @Value("${knowledge-base.chunking.semantic-threshold:0.7}")
    private double defaultSemanticThreshold;

    /**
     * 对解析后的文档进行语义切片
     *
     * @param parsedDocument 解析后的文档
     * @param chunkSize 切片大小
     * @param chunkOverlap 重叠大小
     * @param semanticThreshold 语义相似度阈值
     * @return 切片列表
     */
    public List<DocumentChunk> chunkDocument(
            ParsedDocument parsedDocument,
            Integer chunkSize,
            Integer chunkOverlap,
            Double semanticThreshold) {

        int actualChunkSize = chunkSize != null ? chunkSize : defaultChunkSize;
        int actualOverlap = chunkOverlap != null ? chunkOverlap : defaultChunkOverlap;
        double actualThreshold = semanticThreshold != null ? semanticThreshold : defaultSemanticThreshold;

        log.info("开始语义切片，文档: {}, 段落数: {}, 切片大小: {}, 重叠: {}, 阈值: {}",
                parsedDocument.getFileName(),
                parsedDocument.getParagraphs().size(),
                actualChunkSize,
                actualOverlap,
                actualThreshold);

        List<DocumentChunk> chunks = new ArrayList<>();
        List<Paragraph> paragraphs = parsedDocument.getParagraphs();

        if (paragraphs.isEmpty()) {
            return chunks;
        }

        // 简化版本: 基于长度的切片，保留重叠
        // 生产环境可以集成 DeepSeek 进行语义相似度计算
        StringBuilder currentChunk = new StringBuilder();
        Map<String, Object> currentMetadata = new HashMap<>();
        int chunkIndex = 0;

        for (int i = 0; i < paragraphs.size(); i++) {
            Paragraph para = paragraphs.get(i);
            String paraContent = para.getContent();

            // 如果当前切片为空，直接添加
            if (currentChunk.isEmpty()) {
                currentChunk.append(paraContent);
                currentMetadata.putAll(para.getMetadata());
                continue;
            }

            // 检查添加后是否超过切片大小
            int potentialLength = currentChunk.length() + paraContent.length() + 1; // +1 for newline

            if (potentialLength <= actualChunkSize) {
                // 未超过，继续添加
                currentChunk.append("\n").append(paraContent);
                // 合并元数据
                mergeMetadata(currentMetadata, para.getMetadata());
            } else {
                // 超过了，保存当前切片
                String chunkId = buildChunkId(parsedDocument.getDocId(), chunkIndex);
                chunks.add(DocumentChunk.builder()
                        .chunkId(chunkId)
                        .docId(parsedDocument.getDocId())
                        .content(currentChunk.toString())
                        .chunkIndex(chunkIndex++)
                        .metadata(new HashMap<>(currentMetadata))
                        .build());

                // 处理重叠
                String overlapText = getOverlapText(currentChunk.toString(), actualOverlap);
                currentChunk = new StringBuilder();
                if (!overlapText.isEmpty()) {
                    currentChunk.append(overlapText).append("\n");
                }
                currentChunk.append(paraContent);

                // 重置元数据
                currentMetadata = new HashMap<>(para.getMetadata());
            }
        }

        // 添加最后一个切片
        if (currentChunk.length() > 0) {
            String chunkId = buildChunkId(parsedDocument.getDocId(), chunkIndex);
            chunks.add(DocumentChunk.builder()
                    .chunkId(chunkId)
                    .docId(parsedDocument.getDocId())
                    .content(currentChunk.toString())
                    .chunkIndex(chunkIndex)
                    .metadata(currentMetadata)
                    .build());
        }

        log.info("语义切片完成，生成切片数: {}", chunks.size());
        return chunks;
    }

    /**
     * 获取重叠文本
     */
    private String getOverlapText(String text, int overlapSize) {
        if (text.length() <= overlapSize) {
            return text;
        }
        return text.substring(text.length() - overlapSize);
    }

    /**
     * 合并元数据
     */
    private void mergeMetadata(Map<String, Object> target, Map<String, Object> source) {
        // 对于页码，取范围
        if (target.containsKey("page") && source.containsKey("page")) {
            Object targetPage = target.get("page");
            Object sourcePage = source.get("page");
            if (!targetPage.equals(sourcePage)) {
                target.put("page", targetPage + "-" + sourcePage);
            }
        } else if (source.containsKey("page")) {
            target.put("page", source.get("page"));
        }

        // 对于其他字段，保留或追加
        source.forEach((key, value) -> {
            if (!"page".equals(key) && !target.containsKey(key)) {
                target.put(key, value);
            }
        });
    }

    private String buildChunkId(String docId, int chunkIndex) {
        return docId + "_" + chunkIndex;
    }
}
