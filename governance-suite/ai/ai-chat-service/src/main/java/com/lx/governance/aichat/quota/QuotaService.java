package com.lx.governance.aichat.quota;

import com.lx.governance.aichat.exception.QuotaException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 配额服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final QuotaRepository quotaRepository;
    private final QuotaUsageRepository quotaUsageRepository;
    private final TransactionTemplate transactionTemplate;

    private static final String QUOTA_CACHE_PREFIX = "quota:";

    @Value("${ai-chat.quota.enabled:true}")
    private boolean quotaEnabled;

    @Value("${ai-chat.quota.default-type:daily}")
    private String defaultQuotaType;

    @Value("${ai-chat.quota.default-limit:100000}")
    private int defaultQuotaLimit;

    /**
     * 获取用户配额
     */
    public UserQuota getUserQuota(String customerId) {
        synchronized (quotaRepository) {
            if (!quotaEnabled) {
                return buildDisabledQuota(customerId);
            }

            // 账户存储是结算事实来源，缓存快照不参与读改写。
            // 从当前账户存储获取
            UserQuota quota = quotaRepository.findByCustomerId(customerId);
            if (quota == null) {
                // 使用默认配额
                quota = createDefaultQuota(customerId);
            }

            // 检查是否需要重置
            if (quota.needsReset()) {
                quota = resetQuota(quota);
            }

            return quota;
        }
    }

    /**
     * 检查配额是否足够
     *
     * @throws QuotaException 如果配额不足
     */
    public void checkQuota(String customerId, int requiredTokens) {
        if (!quotaEnabled) {
            log.debug("全局配额开关关闭，跳过检查 - customerId: {}", customerId);
            return;
        }

        UserQuota quota = getUserQuota(customerId);

        if (!quota.getEnabled()) {
            log.warn("用户配额未启用 - customerId: {}", customerId);
            return;
        }

        if (quota.getRemaining() < requiredTokens) {
            throw QuotaException.exceeded(customerId, quota.getQuotaUsed(), quota.getQuotaLimit());
        }
    }

    /**
     * 消费配额
     */
    @Transactional
    public void consumeQuota(String customerId, String conversationId, String messageId,
                             int promptTokens, int completionTokens) {
        synchronized (quotaRepository) {
            if (!quotaEnabled) {
                log.debug("全局配额开关关闭，跳过消费 - customerId: {}", customerId);
                return;
            }

            UserQuota quota = getUserQuota(customerId);

            if (!quota.getEnabled()) {
                log.debug("用户配额未启用，跳过消费 - customerId: {}", customerId);
                return;
            }

            if (promptTokens < 0 || completionTokens < 0) {
                throw new IllegalArgumentException("Token 用量不能为负数");
            }
            if (quotaUsageRepository.containsMessage(customerId, conversationId, messageId)) {
                return;
            }
            int totalTokens = Math.addExact(promptTokens, completionTokens);

            // 更新配额使用量
            quota.setQuotaUsed(Math.addExact(quota.getQuotaUsed(), totalTokens));
            quota.setUpdateTime(LocalDateTime.now());
            // 记录使用历史
            QuotaUsageRecord record = QuotaUsageRecord.builder()
                    .customerId(customerId)
                    .conversationId(conversationId)
                    .messageId(messageId)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(totalTokens)
                    .usageTime(LocalDateTime.now())
                    .quotaType(quota.getQuotaType().getCode())
                    .build();
            quotaUsageRepository.save(record);
            quotaRepository.update(quota);

            // 清除缓存
            String cacheKey = QUOTA_CACHE_PREFIX + customerId;
            invalidateCache(cacheKey);

            log.info("配额已消费 - customerId: {}, tokens: {}, remaining: {}/{}",
                    customerId, totalTokens, quota.getRemaining(), quota.getQuotaLimit());
        }
    }

    /**
     * 创建默认配额
     */
    public UserQuota createDefaultQuota(String customerId) {
        synchronized (quotaRepository) {
            UserQuota existing = quotaRepository.findByCustomerId(customerId);
            if (existing != null) {
                return existing;
            }
            return transactionTemplate.execute(status -> {
                QuotaType quotaType = parseDefaultQuotaType();
                UserQuota quota = UserQuota.builder()
                        .customerId(customerId)
                        .quotaType(quotaType)
                        .quotaLimit(defaultQuotaLimit)
                        .quotaUsed(0)
                        .resetTime(calculateNextResetTime(quotaType))
                        .createTime(LocalDateTime.now())
                        .updateTime(LocalDateTime.now())
                        .enabled(true)
                        .build();

                quotaRepository.save(quota);
                log.info("创建默认配额 - customerId: {}, type: {}, limit: {}",
                        customerId, quota.getQuotaType(), quota.getQuotaLimit());

                return quota;
            });
        }
    }

    /**
     * 重置配额
     */
    public UserQuota resetQuota(UserQuota quota) {
        synchronized (quotaRepository) {
            UserQuota current = quotaRepository.findByCustomerId(quota.getCustomerId());
            if (current == null) {
                return createDefaultQuota(quota.getCustomerId());
            }
            return transactionTemplate.execute(status -> {
                log.info("重置配额 - customerId: {}, oldUsed: {}", quota.getCustomerId(), quota.getQuotaUsed());

                current.setQuotaUsed(0);
                current.setResetTime(current.calculateNextResetTime());
                current.setUpdateTime(LocalDateTime.now());
                quotaRepository.update(current);

                // 清除缓存
                String cacheKey = QUOTA_CACHE_PREFIX + quota.getCustomerId();
                invalidateCache(cacheKey);

                return current;
            });
        }
    }

    /**
     * 更新用户配额（管理员操作）
     */
    @Transactional
    public void updateQuota(String customerId, QuotaType quotaType, int quotaLimit) {
        synchronized (quotaRepository) {
            UserQuota quota = getUserQuota(customerId);
            quota.setQuotaType(quotaType);
            quota.setQuotaLimit(quotaLimit);
            quota.setResetTime(calculateNextResetTime(quotaType));
            quota.setUpdateTime(LocalDateTime.now());
            quotaRepository.update(quota);

            // 清除缓存
            String cacheKey = QUOTA_CACHE_PREFIX + customerId;
            invalidateCache(cacheKey);

            log.info("更新配额 - customerId: {}, type: {}, limit: {}", customerId, quotaType, quotaLimit);
        }
    }

    /**
     * 查询配额使用历史
     */
    public List<QuotaUsageRecord> getUsageHistory(String customerId, LocalDateTime startTime, LocalDateTime endTime) {
        return quotaUsageRepository.findByCustomerIdAndTimeRange(customerId, startTime, endTime);
    }

    /**
     * 获取配额统计
     */
    public QuotaStatistics getStatistics(String customerId, LocalDateTime startTime, LocalDateTime endTime) {
        List<QuotaUsageRecord> records = getUsageHistory(customerId, startTime, endTime);

        int totalTokens = records.stream()
                .mapToInt(QuotaUsageRecord::getTotalTokens)
                .sum();

        int totalPromptTokens = records.stream()
                .mapToInt(QuotaUsageRecord::getPromptTokens)
                .sum();

        int totalCompletionTokens = records.stream()
                .mapToInt(QuotaUsageRecord::getCompletionTokens)
                .sum();

        return QuotaStatistics.builder()
                .customerId(customerId)
                .startTime(startTime)
                .endTime(endTime)
                .totalTokens(totalTokens)
                .totalPromptTokens(totalPromptTokens)
                .totalCompletionTokens(totalCompletionTokens)
                .requestCount(records.size())
                .build();
    }

    private void invalidateCache(String cacheKey) {
        try {
            redisTemplate.delete(cacheKey);
        } catch (RuntimeException ex) {
            log.warn("清理配额旧缓存失败，账户存储中的结算结果仍然有效", ex);
        }
    }

    private LocalDateTime calculateNextResetTime(QuotaType quotaType) {
        LocalDateTime now = LocalDateTime.now();
        switch (quotaType) {
            case DAILY:
                return now.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
            case MONTHLY:
                return now.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
            case TOTAL:
            default:
                return null;
        }
    }

    private UserQuota buildDisabledQuota(String customerId) {
        return UserQuota.builder()
                .customerId(customerId)
                .quotaType(parseDefaultQuotaType())
                .quotaLimit(defaultQuotaLimit)
                .quotaUsed(0)
                .resetTime(null)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .enabled(false)
                .build();
    }

    private QuotaType parseDefaultQuotaType() {
        try {
            return QuotaType.valueOf(defaultQuotaType.trim().toUpperCase());
        } catch (Exception e) {
            log.warn("无法解析默认配额类型，回退 DAILY - configuredType: {}", defaultQuotaType);
            return QuotaType.DAILY;
        }
    }
}
