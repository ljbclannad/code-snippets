package com.lx.governance.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 挂号限流配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "appointment.rate-limit")
public class AppointmentRateLimitProperties {

    /**
     * 是否启用限流
     */
    private boolean enabled = true;

    /**
     * 目标路径
     */
    private String targetPath = "/api/appointment/grab";

    /**
     * 热门科室限流配置
     */
    private RateLimitConfig hotDept = new RateLimitConfig(20, 40);

    /**
     * 普通科室限流配置
     */
    private RateLimitConfig normalDept = new RateLimitConfig(10, 20);

    /**
     * 用户级限流配置
     */
    private RateLimitConfig user = new RateLimitConfig(2, 5);

    /**
     * 热门科室缓存配置
     */
    private CacheConfig hotDeptCache = new CacheConfig(1000, 60);

    @Data
    public static class RateLimitConfig {
        /**
         * 每秒补充速率
         */
        private int replenishRate;

        /**
         * 突发容量
         */
        private int burstCapacity;

        public RateLimitConfig() {}

        public RateLimitConfig(int replenishRate, int burstCapacity) {
            this.replenishRate = replenishRate;
            this.burstCapacity = burstCapacity;
        }
    }

    @Data
    public static class CacheConfig {
        /**
         * 最大缓存条目数
         */
        private int maxSize;

        /**
         * 缓存过期时间（秒）
         */
        private int expireAfterWriteSeconds;

        public CacheConfig() {}

        public CacheConfig(int maxSize, int expireAfterWriteSeconds) {
            this.maxSize = maxSize;
            this.expireAfterWriteSeconds = expireAfterWriteSeconds;
        }
    }
}
