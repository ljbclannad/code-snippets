package com.lx.governance.common.bulkhead;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "bulkhead")
public class BulkheadProperties {
    private boolean enabled = true;
    private PoolConfig defaultConfig = new PoolConfig();
    private Map<String, PoolConfig> services = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public PoolConfig getDefaultConfig() {
        return defaultConfig;
    }

    public void setDefaultConfig(PoolConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    public Map<String, PoolConfig> getServices() {
        return services;
    }

    public void setServices(Map<String, PoolConfig> services) {
        this.services = services;
    }

    public PoolConfig getConfigForService(String serviceName) {
        PoolConfig serviceConfig = services.get(serviceName);
        if (serviceConfig == null) {
            return defaultConfig;
        }
        return mergeConfig(defaultConfig, serviceConfig);
    }

    private PoolConfig mergeConfig(PoolConfig defaultCfg, PoolConfig serviceCfg) {
        PoolConfig merged = new PoolConfig();
        merged.setCorePoolSize(serviceCfg.getCorePoolSize() != null ?
            serviceCfg.getCorePoolSize() : defaultCfg.getCorePoolSize());
        merged.setMaxPoolSize(serviceCfg.getMaxPoolSize() != null ?
            serviceCfg.getMaxPoolSize() : defaultCfg.getMaxPoolSize());
        merged.setQueueCapacity(serviceCfg.getQueueCapacity() != null ?
            serviceCfg.getQueueCapacity() : defaultCfg.getQueueCapacity());
        merged.setTimeoutSeconds(serviceCfg.getTimeoutSeconds() != null ?
            serviceCfg.getTimeoutSeconds() : defaultCfg.getTimeoutSeconds());
        merged.setKeepAliveSeconds(serviceCfg.getKeepAliveSeconds() != null ?
            serviceCfg.getKeepAliveSeconds() : defaultCfg.getKeepAliveSeconds());
        return merged;
    }

    public static class PoolConfig {
        private Integer corePoolSize = 5;
        private Integer maxPoolSize = 10;
        private Integer queueCapacity = 20;
        private Integer timeoutSeconds = 2;
        private Integer keepAliveSeconds = 60;

        public Integer getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(Integer corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public Integer getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(Integer maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public Integer getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(Integer queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public Integer getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(Integer timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public Integer getKeepAliveSeconds() {
            return keepAliveSeconds;
        }

        public void setKeepAliveSeconds(Integer keepAliveSeconds) {
            this.keepAliveSeconds = keepAliveSeconds;
        }

        @Override
        public String toString() {
            return "{corePoolSize=" + corePoolSize +
                    ", maxPoolSize=" + maxPoolSize +
                    ", queueCapacity=" + queueCapacity +
                    ", timeoutSeconds=" + timeoutSeconds +
                    ", keepAliveSeconds=" + keepAliveSeconds + "}";
        }
    }
}
