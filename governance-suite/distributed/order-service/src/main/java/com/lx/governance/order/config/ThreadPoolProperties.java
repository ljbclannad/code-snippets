package com.lx.governance.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.thread-pool")
public class ThreadPoolProperties {

    private PoolConfig io = new PoolConfig();
    private PoolConfig cpu = new PoolConfig();
    private PoolConfig biz = new PoolConfig();
    private ScheduledPoolConfig scheduled = new ScheduledPoolConfig();

    public PoolConfig getIo() {
        return io;
    }

    public void setIo(PoolConfig io) {
        this.io = io;
    }

    public PoolConfig getCpu() {
        return cpu;
    }

    public void setCpu(PoolConfig cpu) {
        this.cpu = cpu;
    }

    public PoolConfig getBiz() {
        return biz;
    }

    public void setBiz(PoolConfig biz) {
        this.biz = biz;
    }

    public ScheduledPoolConfig getScheduled() {
        return scheduled;
    }

    public void setScheduled(ScheduledPoolConfig scheduled) {
        this.scheduled = scheduled;
    }

    public static class PoolConfig {
        private int coreSize;
        private int maxSize;
        private int queueCapacity;
        private int keepAliveSeconds = 60;
        private String threadNamePrefix;

        public int getCoreSize() {
            return coreSize;
        }

        public void setCoreSize(int coreSize) {
            this.coreSize = coreSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getKeepAliveSeconds() {
            return keepAliveSeconds;
        }

        public void setKeepAliveSeconds(int keepAliveSeconds) {
            this.keepAliveSeconds = keepAliveSeconds;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }
    }

    public static class ScheduledPoolConfig {
        private int poolSize = 4;
        private String threadNamePrefix;

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }
    }
}
