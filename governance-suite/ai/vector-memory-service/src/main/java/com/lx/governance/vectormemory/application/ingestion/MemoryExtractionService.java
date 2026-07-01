package com.lx.governance.vectormemory.application.ingestion;

import com.lx.governance.vectormemory.domain.lifecycle.MemoryLifecycleIngestionPipeline;
import com.lx.governance.vectormemory.integration.ai.DeepSeekService;

import com.lx.governance.vectormemory.dto.MemoryExtractionResult;
import com.lx.governance.vectormemory.dto.MemoryExtractionResult.ExtractedMemory;
import com.lx.governance.vectormemory.dto.MemoryLifecycleDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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

    private final DeepSeekService deepSeekService;
    private final MemoryLifecycleIngestionPipeline memoryLifecycleIngestionPipeline;

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
            MemoryExtractionResult result = deepSeekService.extractMemories(userMessage, aiResponse);

            if (result == null || result.getMemories() == null || result.getMemories().isEmpty()) {
                log.info("未提取到任何记忆 - groupId: {}, customerId: {}", groupId, customerId);
                return;
            }

            int totalExtracted = result.getMemories().size();
            log.info("提取到 {} 条记忆 - groupId: {}, customerId: {}", totalExtracted, groupId, customerId);

            int acceptedCount = 0;
            int rejectedCount = 0;

            for (ExtractedMemory extracted : result.getMemories()) {
                try {
                    MemoryLifecycleDecision decision = memoryLifecycleIngestionPipeline.ingest(
                            groupId,
                            customerId,
                            extracted,
                            truncate(userMessage + " | " + aiResponse, 500));
                    if (decision.getAction() == MemoryLifecycleDecision.LifecycleAction.INGEST_CANDIDATE) {
                        acceptedCount++;
                    } else {
                        rejectedCount++;
                    }
                } catch (Exception e) {
                    rejectedCount++;
                    log.error("保存记忆失败: {}, 错误: {}", truncate(extracted.getContent(), 50), e.getMessage(), e);
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("记忆提取完成 - groupId: {}, customerId: {}, 提取: {}, 接受: {}, 拒绝: {}, 耗时: {}ms",
                    groupId, customerId, totalExtracted, acceptedCount, rejectedCount, duration);

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
        memoryLifecycleIngestionPipeline.ingest(
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
