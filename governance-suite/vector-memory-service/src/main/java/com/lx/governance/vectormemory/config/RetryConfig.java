package com.lx.governance.vectormemory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * 重试配置
 *
 * 配置重试策略，用于向量存储等可能失败的操作
 */
@Configuration
public class RetryConfig {

    /**
     * 重试模板
     *
     * @return RetryTemplate
     */
    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();

        // 简单重试策略：最多重试 3 次
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(3);
        retryTemplate.setRetryPolicy(retryPolicy);

        // 固定退避策略：固定间隔 2 秒
        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(2000L); // 2 秒
        retryTemplate.setBackOffPolicy(backOffPolicy);

        return retryTemplate;
    }
}
