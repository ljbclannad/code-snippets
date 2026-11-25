package com.lx.governance.aichat.quota;

import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 配额使用记录存储层
 * 简化实现，使用内存存储
 */
@Repository
public class QuotaUsageRepository {

    private final ConcurrentHashMap<Long, QuotaUsageRecord> recordStore = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    public boolean containsMessage(String customerId, String conversationId, String messageId) {
        return messageId != null && !messageId.isBlank() && recordStore.values().stream().anyMatch(record ->
                Objects.equals(customerId, record.getCustomerId())
                        && Objects.equals(conversationId, record.getConversationId())
                        && Objects.equals(messageId, record.getMessageId()));
    }

    public void save(QuotaUsageRecord record) {
        record.setId(idGenerator.getAndIncrement());
        recordStore.put(record.getId(), record);
    }

    public List<QuotaUsageRecord> findByCustomerIdAndTimeRange(
            String customerId,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        return recordStore.values().stream()
                .filter(record -> record.getCustomerId().equals(customerId))
                .filter(record -> {
                    LocalDateTime usageTime = record.getUsageTime();
                    return !usageTime.isBefore(startTime) && !usageTime.isAfter(endTime);
                })
                .collect(Collectors.toList());
    }

    public List<QuotaUsageRecord> findByCustomerId(String customerId) {
        return recordStore.values().stream()
                .filter(record -> record.getCustomerId().equals(customerId))
                .collect(Collectors.toList());
    }
}
