package com.lx.governance.vectormemory.service;

import com.lx.governance.vectormemory.dto.MemoryExtractionResult;
import com.lx.governance.vectormemory.dto.MemoryExtractionResult.ExtractedMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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

    private static final int LOG_CONTENT_MAX_LENGTH = 50;

    private final DeepSeekDirectService deepSeekDirectService;
    private final RedisMemoryIndexService redisMemoryIndexService;
    private final CandidateMemoryService candidateMemoryService;

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

            // Step 3: 去重并进入候选态处理
            int savedCount = 0;
            int duplicateCount = 0;

            for (ExtractedMemory extracted : filteredMemories) {
                // Step 3.1: Redis快速去重检查
                if (redisMemoryIndexService != null) {
                    try {
                        if (redisMemoryIndexService.isDuplicate(groupId, extracted.getContent())) {
                            log.info("记忆已存在（Redis去重）: {}", truncate(extracted.getContent(), LOG_CONTENT_MAX_LENGTH));
                            duplicateCount++;
                            continue;
                        }
                    } catch (Exception e) {
                        log.warn("Redis去重检查失败（isDuplicate操作异常），继续Milvus去重 - groupId: {}, 错误: {}",
                                groupId, e.getMessage());
                    }
                }

                // Step 3.2: 候选态处理
                try {
                    candidateMemoryService.processCandidate(
                            groupId,
                            customerId,
                            extracted,
                            truncate(userMessage + " | " + aiResponse, 500));
                    savedCount++;
                } catch (Exception e) {
                    log.error("保存记忆失败: {}, 错误: {}", truncate(extracted.getContent(), LOG_CONTENT_MAX_LENGTH), e.getMessage(), e);
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
     * 兼容旧测试/旧调用入口：直接将提取结果送入候选态处理
     */
    public void saveMemory(String groupId, String customerId, ExtractedMemory extracted,
                           String userMessage, String aiResponse) {
        candidateMemoryService.processCandidate(
                groupId,
                customerId,
                extracted,
                truncate(userMessage + " | " + aiResponse, 500));
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

}
