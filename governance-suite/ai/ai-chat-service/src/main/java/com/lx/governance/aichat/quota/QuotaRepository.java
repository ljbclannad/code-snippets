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
        return quotaStore.get(customerId);
    }

    public void save(UserQuota quota) {
        quotaStore.put(quota.getCustomerId(), quota);
    }

    public void update(UserQuota quota) {
        quotaStore.put(quota.getCustomerId(), quota);
    }

    public void delete(String customerId) {
        quotaStore.remove(customerId);
    }
}
