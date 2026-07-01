package com.lx.governance.aichat.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import com.lx.governance.aichat.client.UserProfileClientFixed;
import com.lx.governance.aichat.client.VectorMemoryClientFixed;
import com.lx.governance.aichat.config.RabbitMQConfig;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AiAsyncBacklogMetricsTest {

    @Test
    void shouldExposeQueueBacklogAndPendingRpcRequestGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AmqpAdmin amqpAdmin = mock(AmqpAdmin.class);
        UserProfileClientFixed userProfileClient = mock(UserProfileClientFixed.class);
        VectorMemoryClientFixed vectorMemoryClient = mock(VectorMemoryClientFixed.class);

        Properties chatHistoryProperties = new Properties();
        chatHistoryProperties.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 7);
        chatHistoryProperties.put(RabbitAdmin.QUEUE_CONSUMER_COUNT, 2);
        when(amqpAdmin.getQueueProperties(RabbitMQConfig.CHAT_HISTORY_SYNC_QUEUE)).thenReturn(chatHistoryProperties);

        Properties memoryExtractionProperties = new Properties();
        memoryExtractionProperties.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 5);
        memoryExtractionProperties.put(RabbitAdmin.QUEUE_CONSUMER_COUNT, 1);
        when(amqpAdmin.getQueueProperties("memory.extraction.queue")).thenReturn(memoryExtractionProperties);

        Properties userProfileReplyProperties = new Properties();
        userProfileReplyProperties.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 1);
        when(amqpAdmin.getQueueProperties("ai.chat.user.profile.reply")).thenReturn(userProfileReplyProperties);

        Properties vectorMemoryReplyProperties = new Properties();
        vectorMemoryReplyProperties.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 3);
        when(amqpAdmin.getQueueProperties("ai.chat.vector.memory.reply")).thenReturn(vectorMemoryReplyProperties);

        when(userProfileClient.getPendingRequestCount()).thenReturn(4);
        when(vectorMemoryClient.getPendingRequestCount()).thenReturn(6);

        new AiAsyncBacklogMetrics(registry, amqpAdmin, userProfileClient, vectorMemoryClient);

        assertEquals(7.0, registry.get("ai.async.queue.backlog")
                .tag("queue", "chat_history_sync")
                .tag("metric_type", "ready")
                .gauge()
                .value());
        assertEquals(1.0, registry.get("ai.async.queue.backlog")
                .tag("queue", "memory_extraction")
                .tag("metric_type", "consumers")
                .gauge()
                .value());
        assertEquals(4.0, registry.get("ai.rpc.pending.requests")
                .tag("client", "user_profile")
                .gauge()
                .value());
        assertEquals(6.0, registry.get("ai.rpc.pending.requests")
                .tag("client", "vector_memory")
                .gauge()
                .value());
    }
}
