package com.lx.governance.aichat.integration;

import com.lx.governance.aichat.document.ChatHistoryDocument;
import com.lx.governance.aichat.dto.ChatHistorySyncMessage;
import com.lx.governance.aichat.service.ChatHistorySearchService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对话历史 Elasticsearch 集成测试
 * 测试完整的数据流：RabbitMQ -> ES -> 搜索
 */
@SpringBootTest
@ActiveProfiles("test")
@Slf4j
class ChatHistoryEsIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ChatHistorySearchService searchService;

    private String testCustomerId;
    private String testConversationId;

    @BeforeEach
    void setUp() {
        testCustomerId = "test-customer-" + UUID.randomUUID().toString();
        testConversationId = "test-conversation-" + UUID.randomUUID().toString();
    }

    @Test
    void testEndToEndFlow_MessageToEsToSearch() throws InterruptedException {
        // 1. 准备测试数据
        ChatHistorySyncMessage message = ChatHistorySyncMessage.builder()
                .id(UUID.randomUUID().toString())
                .customerId(testCustomerId)
                .conversationId(testConversationId)
                .userMessage("如何使用 Spring Boot 集成 Elasticsearch？")
                .aiResponse("Spring Boot 提供了 spring-boot-starter-data-elasticsearch 依赖，可以方便地集成 Elasticsearch。")
                .createTime(LocalDateTime.now())
                .build();

        // 2. 发送消息到 RabbitMQ
        log.info("发送测试消息到 RabbitMQ: {}", message);
        rabbitTemplate.convertAndSend(
                "ai.chat.history.sync.exchange",
                "chat.history.sync",
                message
        );

        // 3. 等待消息处理和 ES 索引刷新
        Thread.sleep(3000);

        // 4. 搜索验证
        List<ChatHistoryDocument> results = searchService.findSimilarConversations(
                testCustomerId,
                "Spring Boot Elasticsearch",
                10
        );

        // 5. 断言
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getCustomerId()).isEqualTo(testCustomerId);
        assertThat(results.get(0).getUserMessage()).contains("Spring Boot");
        assertThat(results.get(0).getAiResponse()).contains("Elasticsearch");

        log.info("集成测试通过，找到 {} 条相似对话", results.size());
    }

    @Test
    void testMultipleMessages_SearchReturnsAll() throws InterruptedException {
        // 1. 发送多条消息
        String[] questions = {
                "什么是微服务架构？",
                "如何实现服务间通信？",
                "Spring Cloud 有哪些组件？"
        };

        for (String question : questions) {
            ChatHistorySyncMessage message = ChatHistorySyncMessage.builder()
                    .id(UUID.randomUUID().toString())
                    .customerId(testCustomerId)
                    .conversationId(testConversationId)
                    .userMessage(question)
                    .aiResponse("这是关于 " + question + " 的回答")
                    .createTime(LocalDateTime.now())
                    .build();

            rabbitTemplate.convertAndSend(
                    "ai.chat.history.sync.exchange",
                    "chat.history.sync",
                    message
            );
        }

        // 2. 等待处理
        Thread.sleep(3000);

        // 3. 搜索验证
        List<ChatHistoryDocument> results = searchService.findSimilarConversations(
                testCustomerId,
                "微服务",
                10
        );

        // 4. 断言
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
        log.info("找到 {} 条包含'微服务'的对话", results.size());
    }

    @Test
    void testSearchWithDifferentCustomers_ReturnsOnlyOwnData() throws InterruptedException {
        // 1. 为两个不同客户发送消息
        String customer1 = "customer-1-" + UUID.randomUUID();
        String customer2 = "customer-2-" + UUID.randomUUID();

        ChatHistorySyncMessage message1 = ChatHistorySyncMessage.builder()
                .id(UUID.randomUUID().toString())
                .customerId(customer1)
                .conversationId(UUID.randomUUID().toString())
                .userMessage("客户1的问题")
                .aiResponse("客户1的回答")
                .createTime(LocalDateTime.now())
                .build();

        ChatHistorySyncMessage message2 = ChatHistorySyncMessage.builder()
                .id(UUID.randomUUID().toString())
                .customerId(customer2)
                .conversationId(UUID.randomUUID().toString())
                .userMessage("客户2的问题")
                .aiResponse("客户2的回答")
                .createTime(LocalDateTime.now())
                .build();

        rabbitTemplate.convertAndSend("ai.chat.history.sync.exchange", "chat.history.sync", message1);
        rabbitTemplate.convertAndSend("ai.chat.history.sync.exchange", "chat.history.sync", message2);

        // 2. 等待处理
        Thread.sleep(3000);

        // 3. 搜索客户1的数据
        List<ChatHistoryDocument> results1 = searchService.findSimilarConversations(customer1, "问题", 10);

        // 4. 断言只返回客户1的数据
        assertThat(results1).isNotEmpty();
        assertThat(results1).allMatch(doc -> doc.getCustomerId().equals(customer1));

        log.info("数据隔离测试通过");
    }
}
