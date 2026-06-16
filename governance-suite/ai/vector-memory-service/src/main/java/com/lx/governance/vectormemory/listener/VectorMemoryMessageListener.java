package com.lx.governance.vectormemory.listener;

import com.lx.governance.common.trace.Trace;
import com.lx.governance.vectormemory.dto.ExtractionRequestMessage;
import com.lx.governance.vectormemory.dto.MemoryItemDTO;
import com.lx.governance.vectormemory.dto.MemoryRequestMessage;
import com.lx.governance.vectormemory.dto.MemoryResponseMessage;
import com.lx.governance.vectormemory.dto.StructuredMemoryQuery;
import com.lx.governance.vectormemory.service.MemoryExtractionService;
import com.lx.governance.vectormemory.service.MemoryRetrievalService;
import com.lx.governance.vectormemory.service.StructuredMemoryQueryCompiler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量记忆消息监听器
 * 监听 RabbitMQ 消息队列，处理记忆检索和提取请求
 *
 * @author LeJinBo
 * @since 2026-05-13
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorMemoryMessageListener {

    private final MemoryRetrievalService memoryRetrievalService;
    private final MemoryExtractionService memoryExtractionService;
    private final StructuredMemoryQueryCompiler structuredMemoryQueryCompiler;
    private final RabbitTemplate rabbitTemplate;

    /**
     * 处理记忆检索请求
     * 监听队列: vector.memory.request
     * 响应路由: vector.memory.exchange -> vector.memory.response.{correlationId}
     *
     * @param request 记忆检索请求消息
     * @param message 原始消息（用于获取 replyTo）
     */
    @RabbitListener(queues = "vector.memory.request")
    public void handleMemoryRequest(MemoryRequestMessage request, Message message) {
        // 设置 traceId（如果请求中包含，使用 correlationId 作为 traceId）
        if (request.getCorrelationId() != null) {
            Trace.set(request.getCorrelationId());
        } else {
            Trace.getOrCreate();
        }

        String replyTo = message.getMessageProperties().getReplyTo();
        log.info("收到记忆检索请求 - correlationId: {}, groupId: {}, customerId: {}, query: {}, topK: {}, replyTo: {}",
                request.getCorrelationId(), request.getGroupId(), request.getCustomerId(),
                truncate(request.getQuery(), 50), request.getTopK(), replyTo);

        MemoryResponseMessage response = new MemoryResponseMessage();
        response.setCorrelationId(request.getCorrelationId());
        response.setTimestamp(System.currentTimeMillis());

        try {
            // 参数校验
            if (request.getGroupId() == null || request.getCustomerId() == null || request.getQuery() == null) {
                throw new IllegalArgumentException("groupId, customerId, and query are required");
            }

            int topK = request.getTopK() != null ? request.getTopK() : 5;
            if (topK <= 0 || topK > 20) {
                throw new IllegalArgumentException("topK must be between 1 and 20");
            }

            StructuredMemoryQuery.MemoryFilter filter = request.getStructuredQuery() != null
                    ? request.getStructuredQuery().getFilter()
                    : null;
            com.lx.governance.vectormemory.dto.MemorySearchFilter compiledFilter =
                    structuredMemoryQueryCompiler.compile(
                            request.getStructuredQuery(),
                            com.lx.governance.vectormemory.dto.MemorySearchFilter.builder()
                                    .memoryTypes(filter != null ? filter.getMemoryTypes() : request.getMemoryTypes())
                                    .minConfidence(filter != null ? filter.getMinConfidence() : request.getMinConfidence())
                                    .verifiedOnly(filter != null ? filter.getVerifiedOnly() : request.getVerifiedOnly())
                                    .recentDays(filter != null ? filter.getRecentDays() : request.getRecentDays())
                                    .build());
            String effectiveQuery = request.getStructuredQuery() != null
                    && request.getStructuredQuery().getSemanticQuery() != null
                    && !request.getStructuredQuery().getSemanticQuery().isBlank()
                    ? request.getStructuredQuery().getSemanticQuery()
                    : request.getQuery();

            // 调用记忆检索服务
            List<MemoryItemDTO> memories = memoryRetrievalService.retrieveGroupMemories(
                    effectiveQuery,
                    request.getGroupId(),
                    request.getCustomerId(),
                    topK,
                    compiledFilter.getMemoryTypes(),
                    compiledFilter.getMinConfidence(),
                    Boolean.TRUE.equals(compiledFilter.getVerifiedOnly()),
                    compiledFilter.getRecentDays()
            );

            // 构建成功响应
            response.setSuccess(true);
            response.setData(memories);

            log.info("记忆检索成功 - correlationId: {}, 检索到 {} 条记忆",
                    request.getCorrelationId(), memories.size());

        } catch (Exception e) {
            // 构建失败响应
            response.setSuccess(false);
            response.setErrorMessage(e.getMessage());
            response.setData(null);

            log.error("记忆检索失败 - correlationId: {}, 错误: {}",
                    request.getCorrelationId(), e.getMessage(), e);
        }

        // 发送响应：优先使用 replyTo，否则使用动态路由键
        try {
            String correlationId = request.getCorrelationId();
            if (replyTo != null && !replyTo.isEmpty()) {
                // 必须设置 correlationId header，否则 RPC 客户端无法匹配响应
                rabbitTemplate.convertAndSend("", replyTo, response, msg -> {
                    msg.getMessageProperties().setCorrelationId(correlationId);
                    return msg;
                });
                log.info("记忆检索响应已发送到 replyTo 队列 - correlationId: {}, replyTo: {}, success: {}",
                        correlationId, replyTo, response.getSuccess());
            } else {
                String routingKey = "vector.memory.response." + correlationId;
                rabbitTemplate.convertAndSend("vector.memory.exchange", routingKey, response, msg -> {
                    msg.getMessageProperties().setCorrelationId(correlationId);
                    return msg;
                });
                log.info("记忆检索响应已发送到路由键 - correlationId: {}, routingKey: {}, success: {}",
                        correlationId, routingKey, response.getSuccess());
            }
        } catch (Exception e) {
            log.error("发送记忆检索响应失败 - correlationId: {}, 错误: {}",
                    request.getCorrelationId(), e.getMessage(), e);
        } finally {
            // 清理 traceId
            Trace.clear();
        }
    }

    /**
     * 处理记忆提取请求
     * 监听队列: memory.extraction.queue
     * 异步提取记忆，不返回响应
     *
     * @param request 记忆提取请求消息
     */
    @RabbitListener(queues = "memory.extraction.queue")
    public void handleExtractionRequest(ExtractionRequestMessage request) {
        // 设置 traceId
        if (request.getCorrelationId() != null) {
            Trace.set(request.getCorrelationId());
        } else {
            Trace.getOrCreate();
        }

        log.info("收到记忆提取请求 - correlationId: {}, groupId: {}, customerId: {}, userMessage长度: {}, aiResponse长度: {}",
                request.getCorrelationId(), request.getGroupId(), request.getCustomerId(),
                request.getUserMessage() != null ? request.getUserMessage().length() : 0,
                request.getAiResponse() != null ? request.getAiResponse().length() : 0);

        try {
            // 参数校验
            if (request.getGroupId() == null || request.getCustomerId() == null ||
                    request.getUserMessage() == null || request.getAiResponse() == null) {
                throw new IllegalArgumentException("groupId, customerId, userMessage, and aiResponse are required");
            }

            // 异步提交记忆提取任务
            memoryExtractionService.extractAndSaveMemories(
                    request.getGroupId(),
                    request.getCustomerId(),
                    request.getUserMessage(),
                    request.getAiResponse()
            );

            log.info("记忆提取任务已提交 - correlationId: {}, groupId: {}, customerId: {}",
                    request.getCorrelationId(), request.getGroupId(), request.getCustomerId());

        } catch (Exception e) {
            log.error("记忆提取任务提交失败 - correlationId: {}, groupId: {}, customerId: {}, 错误: {}",
                    request.getCorrelationId(), request.getGroupId(), request.getCustomerId(),
                    e.getMessage(), e);
        } finally {
            // 清理 traceId
            Trace.clear();
        }
    }

    /**
     * 截断文本用于日志输出
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
