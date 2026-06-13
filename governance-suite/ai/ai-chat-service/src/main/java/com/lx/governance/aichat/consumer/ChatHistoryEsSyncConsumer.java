package com.lx.governance.aichat.consumer;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.lx.governance.aichat.document.ChatHistoryDocument;
import com.lx.governance.aichat.dto.ChatHistorySyncMessage;
import com.lx.governance.aichat.service.ChatHistorySearchService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 对话历史 ES 同步消费者
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatHistoryEsSyncConsumer {

    private final ChatHistorySearchService searchService;

    @RabbitListener(queues = "#{T(com.lx.governance.aichat.config.RabbitMQConfig).CHAT_HISTORY_SYNC_QUEUE}")
    public void syncToEs(ChatHistorySyncMessage message) {
        log.info("收到对话历史同步消息 - id: {}, customerId: {}", message.getId(), message.getCustomerId());

        try {
            // 转换为 ES 文档
            ChatHistoryDocument document = ChatHistoryDocument.builder()
                    .id(String.valueOf(message.getId()))
                    .customerId(message.getCustomerId())
                    .conversationId(message.getConversationId())
                    .userMessage(message.getUserMessage())
                    .aiResponse(message.getAiResponse())
                    .promptTokens(message.getPromptTokens())
                    .completionTokens(message.getCompletionTokens())
                    .totalTokens(message.getTotalTokens())
                    .createTime(message.getCreateTime())
                    .build();

            // 保存到 ES
            searchService.saveToEs(document);

            log.info("对话历史同步成功 - id: {}", message.getId());

        } catch (Exception e) {
            log.error("对话历史同步失败 - id: {}", message.getId(), e);
            throw e; // 触发重试
        }
    }
}
