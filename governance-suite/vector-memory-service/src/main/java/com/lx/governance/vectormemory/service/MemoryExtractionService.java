package com.lx.governance.vectormemory.service;

import com.lx.governance.vectormemory.dto.MemoryExtractionResult;
import com.lx.governance.vectormemory.dto.MemoryExtractionResult.ExtractedMemory;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 记忆提取服务
 * 从对话中异步提取记忆，保存到 MySQL 和 Milvus（双写）
 *
 * @author LeJinBo
 * @since 2026-05-13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryExtractionService {

    private final DeepSeekDirectService deepSeekDirectService;
    private final VectorStoreService vectorStoreService;
    private final MemoryRetrievalService memoryRetrievalService;
    private final AiGroupMemoryMapper memoryMapper;

    /**
     * 异步提取并保存记忆
     *
     * @param groupId     群组ID
     * @param customerId  用户ID
     * @param userMessage 用户消息
     * @param aiResponse  AI响应
     */
    @Async("memoryExtractionExecutor")
    public void extractAndSaveMemories(String groupId, String customerId, String userMessage, String aiResponse) {
        long startTime = System.currentTimeMillis();
        log.info("开始异步提取记忆 - groupId: {}, customerId: {}, userMessage长度: {}, aiResponse长度: {}",
                groupId, customerId, userMessage.length(), aiResponse.length());

        try {
            // Step 1: 调用 DeepSeek 提取记忆
            MemoryExtractionResult result = deepSeekDirectService.extractMemories(userMessage, aiResponse);

            if (result == null || result.getMemories() == null || result.getMemories().isEmpty()) {
                log.info("未提取到任何记忆 - groupId: {}, customerId: {}", groupId, customerId);
                return;
            }

            int totalExtracted = result.getMemories().size();
            log.info("提取到 {} 条记忆 - groupId: {}, customerId: {}", totalExtracted, groupId, customerId);

            // Step 2: 过滤低置信度记忆（confidence < 0.7）
            List<ExtractedMemory> filteredMemories = result.getMemories().stream()
                    .filter(memory -> memory.getConfidence() != null && memory.getConfidence() >= 0.7)
                    .toList();

            int filteredCount = totalExtracted - filteredMemories.size();
            if (filteredCount > 0) {
                log.info("过滤掉 {} 条低置信度记忆（< 0.7）", filteredCount);
            }

            // Step 3: 去重并保存
            int savedCount = 0;
            int duplicateCount = 0;

            for (ExtractedMemory extracted : filteredMemories) {
                // 检查是否存在相似记忆
                boolean hasSimilar = memoryRetrievalService.hasSimilarMemory(groupId, customerId, extracted.getContent());

                if (hasSimilar) {
                    log.debug("跳过重复记忆: {}", truncate(extracted.getContent(), 50));
                    duplicateCount++;
                    continue;
                }

                // 保存记忆
                try {
                    saveMemory(groupId, customerId, extracted, userMessage, aiResponse);
                    savedCount++;
                } catch (Exception e) {
                    log.error("保存记忆失败: {}, 错误: {}", truncate(extracted.getContent(), 50), e.getMessage(), e);
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("记忆提取完成 - groupId: {}, customerId: {}, 提取: {}, 过滤: {}, 去重: {}, 保存: {}, 耗时: {}ms",
                    groupId, customerId, totalExtracted, filteredCount, duplicateCount, savedCount, duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("记忆提取失败 - groupId: {}, customerId: {}, 耗时: {}ms, 错误: {}",
                    groupId, customerId, duration, e.getMessage(), e);
        }
    }

    /**
     * 保存单条记忆（MySQL + Milvus 双写）
     *
     * @param groupId     群组ID
     * @param customerId  用户ID
     * @param extracted   提取的记忆
     * @param userMessage 用户消息
     * @param aiResponse  AI响应
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveMemory(String groupId, String customerId, ExtractedMemory extracted,
                           String userMessage, String aiResponse) {
        log.debug("保存记忆 - groupId: {}, customerId: {}, category: {}, content: {}",
                groupId, customerId, extracted.getCategory(), truncate(extracted.getContent(), 50));

        // Step 1: 构建 AiGroupMemoryEntity
        AiGroupMemoryEntity entity = new AiGroupMemoryEntity();
        entity.setCustomerId(customerId);
        entity.setGroupId(groupId);
        entity.setMemoryType(mapCategoryToType(extracted.getCategory()));
        entity.setMemoryKey(extractKeyFromContent(extracted.getContent()));
        entity.setMemoryContent(extracted.getContent());
        entity.setConfidenceScore(BigDecimal.valueOf(extracted.getConfidence()));
        entity.setSourceMessage(truncate(userMessage + " | " + aiResponse, 500));
        entity.setExtractedAt(LocalDateTime.now());
        entity.setIsDeleted(0);

        // Step 2: 先写 MySQL
        memoryMapper.insert(entity);
        log.debug("MySQL 写入成功 - id: {}", entity.getId());

        // Step 3: 再写 Milvus
        String embeddingId = null;
        try {
            embeddingId = vectorStoreService.saveGroupMemory(entity);
            log.debug("Milvus 写入成功 - embeddingId: {}", embeddingId);

            // Step 4: 更新 embedding_id
            memoryMapper.updateEmbeddingId(entity.getId(), embeddingId);
            log.debug("更新 embeddingId 成功 - id: {}, embeddingId: {}", entity.getId(), embeddingId);

        } catch (Exception e) {
            log.error("Milvus 写入失败 - id: {}, 错误: {}, 记录到补偿队列（TODO）",
                    entity.getId(), e.getMessage(), e);
            // TODO: 记录到补偿队列，后续重试
            throw new RuntimeException("Milvus 写入失败: " + e.getMessage(), e);
        }

        log.info("记忆保存成功 - id: {}, embeddingId: {}, category: {}, confidence: {}",
                entity.getId(), embeddingId, extracted.getCategory(), extracted.getConfidence());
    }

    /**
     * 截断文本
     *
     * @param text      文本
     * @param maxLength 最大长度
     * @return 截断后的文本
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

    /**
     * 映射 category 到 memoryType
     *
     * @param category 类别（preference, health, goal, fact, context）
     * @return memoryType（PREFERENCE, SYMPTOM, OTHER）
     */
    private String mapCategoryToType(String category) {
        if (category == null) {
            return "OTHER";
        }
        return switch (category.toLowerCase()) {
            case "preference" -> "PREFERENCE";
            case "health" -> "SYMPTOM";
            case "goal" -> "OTHER";
            case "fact" -> "OTHER";
            case "context" -> "OTHER";
            default -> "OTHER";
        };
    }

    /**
     * 从内容中提取关键词作为 memoryKey
     *
     * @param content 记忆内容
     * @return 关键词（取前10个字符）
     */
    private String extractKeyFromContent(String content) {
        if (content == null || content.isEmpty()) {
            return "unknown";
        }
        // 简单实现：取前10个字符作为关键词
        return content.length() <= 10 ? content : content.substring(0, 10);
    }
}
