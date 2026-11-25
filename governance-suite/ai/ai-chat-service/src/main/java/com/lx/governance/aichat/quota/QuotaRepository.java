package com.lx.governance.aichat.quota;

import org.springframework.stereotype.Repository;

/**
 * 配额存储层
 * 简化实现，使用内存存储
 */
@Repository
public class QuotaRepository {

    private final java.util.Map<String, UserQuota> quotaStore = new java.util.concurrent.ConcurrentHashMap<>();

    public UserQuota findByCustomerId(String customerId) {
        UserQuota quota = quotaStore.get(customerId);
        return quota == null ? null : quota.toBuilder().build();
    }

    public void save(UserQuota quota) {
        quotaStore.put(quota.getCustomerId(), quota.toBuilder().build());
    }

    public void update(UserQuota quota) {
        quotaStore.put(quota.getCustomerId(), quota.toBuilder().build());
    }

    public void delete(String customerId) {
        quotaStore.remove(customerId);
    }
}
