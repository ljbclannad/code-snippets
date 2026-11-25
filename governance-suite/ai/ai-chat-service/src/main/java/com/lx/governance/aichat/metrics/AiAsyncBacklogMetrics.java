package com.lx.governance.aichat.metrics;

import java.util.Properties;
import java.util.function.ToDoubleFunction;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.lx.governance.aichat.client.UserProfileClientFixed;
import com.lx.governance.aichat.client.VectorMemoryClientFixed;
import com.lx.governance.aichat.config.RabbitMQConfig;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class AiAsyncBacklogMetrics {

    private final AmqpAdmin amqpAdmin;

    public AiAsyncBacklogMetrics(
            MeterRegistry meterRegistry,
            AmqpAdmin amqpAdmin,
            UserProfileClientFixed userProfileClient,
            VectorMemoryClientFixed vectorMemoryClient,
            @Qualifier("userProfileReplyQueue") Queue userProfileReplyQueue,
            @Qualifier("vectorMemoryReplyQueue") Queue vectorMemoryReplyQueue) {
        this.amqpAdmin = amqpAdmin;

        bindQueueGauge(meterRegistry, "chat_history_sync", RabbitMQConfig.CHAT_HISTORY_SYNC_QUEUE, "ready");
        bindQueueGauge(meterRegistry, "chat_history_sync", RabbitMQConfig.CHAT_HISTORY_SYNC_QUEUE, "consumers");
        bindQueueGauge(meterRegistry, "memory_extraction", "memory.extraction.queue", "ready");
        bindQueueGauge(meterRegistry, "memory_extraction", "memory.extraction.queue", "consumers");
        bindQueueGauge(meterRegistry, "user_profile_reply", userProfileReplyQueue.getName(), "ready");
        bindQueueGauge(meterRegistry, "vector_memory_reply", vectorMemoryReplyQueue.getName(), "ready");

        Gauge.builder("ai.rpc.pending.requests", userProfileClient, UserProfileClientFixed::getPendingRequestCount)
                .description("Pending user profile RPC requests")
                .tag("client", "user_profile")
                .register(meterRegistry);
        Gauge.builder("ai.rpc.pending.requests", vectorMemoryClient, VectorMemoryClientFixed::getPendingRequestCount)
                .description("Pending vector memory RPC requests")
                .tag("client", "vector_memory")
                .register(meterRegistry);
    }

    private void bindQueueGauge(
            MeterRegistry meterRegistry,
            String queueAlias,
            String queueName,
            String metricType) {
        ToDoubleFunction<AiAsyncBacklogMetrics> valueFunction = "consumers".equals(metricType)
                ? metrics -> metrics.readQueueConsumerCount(queueName)
                : metrics -> metrics.readQueueReadyCount(queueName);
        Gauge.builder("ai.async.queue.backlog", this, valueFunction)
                .description("Async queue backlog and consumer gauges")
                .tag("queue", queueAlias)
                .tag("metric_type", metricType)
                .register(meterRegistry);
    }

    double readQueueReadyCount(String queueName) {
        return readQueueMetric(queueName, RabbitAdmin.QUEUE_MESSAGE_COUNT);
    }

    double readQueueConsumerCount(String queueName) {
        return readQueueMetric(queueName, RabbitAdmin.QUEUE_CONSUMER_COUNT);
    }

    public double chatHistorySyncReadyCount() {
        return readQueueReadyCount(RabbitMQConfig.CHAT_HISTORY_SYNC_QUEUE);
    }

    public double memoryExtractionReadyCount() {
        return readQueueReadyCount("memory.extraction.queue");
    }

    public double userProfilePendingRequestCount(UserProfileClientFixed userProfileClient) {
        return userProfileClient.getPendingRequestCount();
    }

    public double vectorMemoryPendingRequestCount(VectorMemoryClientFixed vectorMemoryClient) {
        return vectorMemoryClient.getPendingRequestCount();
    }

    private double readQueueMetric(String queueName, Object propertyKey) {
        try {
            Properties properties = amqpAdmin.getQueueProperties(queueName);
            if (properties == null) {
                return 0D;
            }
            Object value = properties.get(propertyKey);
            return value instanceof Number ? ((Number) value).doubleValue() : 0D;
        } catch (Exception ignored) {
            return 0D;
        }
    }
}
