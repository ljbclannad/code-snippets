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
import java.util.concurrent.TimeUnit;

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
    private static final long CACHE_TTL_MINUTES = 5;

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
        if (!quotaEnabled) {
            return buildDisabledQuota(customerId);
        }

        // 先从缓存获取
        String cacheKey = QUOTA_CACHE_PREFIX + customerId;
        UserQuota cached = (UserQuota) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 从数据库获取
        UserQuota quota = quotaRepository.findByCustomerId(customerId);
        if (quota == null) {
            // 使用默认配额
            quota = createDefaultQuota(customerId);
        }

        // 检查是否需要重置
        if (quota.needsReset()) {
            quota = resetQuota(quota);
        }

        // 缓存
        redisTemplate.opsForValue().set(cacheKey, quota, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        return quota;
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
        if (!quotaEnabled) {
            log.debug("全局配额开关关闭，跳过消费 - customerId: {}", customerId);
            return;
        }

        UserQuota quota = getUserQuota(customerId);

        if (!quota.getEnabled()) {
            log.debug("用户配额未启用，跳过消费 - customerId: {}", customerId);
            return;
        }

        int totalTokens = promptTokens + completionTokens;

        // 更新配额使用量
        quota.setQuotaUsed(quota.getQuotaUsed() + totalTokens);
        quota.setUpdateTime(LocalDateTime.now());
        quotaRepository.update(quota);

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

        // 清除缓存
        String cacheKey = QUOTA_CACHE_PREFIX + customerId;
        redisTemplate.delete(cacheKey);

        log.info("配额已消费 - customerId: {}, tokens: {}, remaining: {}/{}",
                customerId, totalTokens, quota.getRemaining(), quota.getQuotaLimit());
    }

    /**
     * 创建默认配额
     */
    public UserQuota createDefaultQuota(String customerId) {
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

    /**
     * 重置配额
     */
    public UserQuota resetQuota(UserQuota quota) {
        return transactionTemplate.execute(status -> {
            log.info("重置配额 - customerId: {}, oldUsed: {}", quota.getCustomerId(), quota.getQuotaUsed());

            quota.setQuotaUsed(0);
            quota.setResetTime(quota.calculateNextResetTime());
            quota.setUpdateTime(LocalDateTime.now());
            quotaRepository.update(quota);

            // 清除缓存
            String cacheKey = QUOTA_CACHE_PREFIX + quota.getCustomerId();
            redisTemplate.delete(cacheKey);

            return quota;
        });
    }

    /**
     * 更新用户配额（管理员操作）
     */
    @Transactional
    public void updateQuota(String customerId, QuotaType quotaType, int quotaLimit) {
        UserQuota quota = getUserQuota(customerId);
        quota.setQuotaType(quotaType);
        quota.setQuotaLimit(quotaLimit);
        quota.setResetTime(calculateNextResetTime(quotaType));
        quota.setUpdateTime(LocalDateTime.now());
        quotaRepository.update(quota);

        // 清除缓存
        String cacheKey = QUOTA_CACHE_PREFIX + customerId;
        redisTemplate.delete(cacheKey);

        log.info("更新配额 - customerId: {}, type: {}, limit: {}", customerId, quotaType, quotaLimit);
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
