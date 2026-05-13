package com.lx.governance.common.bulkhead;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

@Component
@Endpoint(id = "bulkhead")
public class BulkheadMetricsEndpoint {

    private final BulkheadThreadPoolManager poolManager;

    public BulkheadMetricsEndpoint(BulkheadThreadPoolManager poolManager) {
        this.poolManager = poolManager;
    }

    @ReadOperation
    public Map<String, PoolMetrics> metrics() {
        Map<String, PoolMetrics> result = new HashMap<>();

        poolManager.getAllPools().forEach((serviceName, pool) -> {
            PoolMetrics metrics = new PoolMetrics();
            metrics.setServiceName(serviceName);
            metrics.setActiveThreads(pool.getActiveCount());
            metrics.setPoolSize(pool.getPoolSize());
            metrics.setCorePoolSize(pool.getCorePoolSize());
            metrics.setMaxPoolSize(pool.getMaximumPoolSize());
            metrics.setQueueSize(pool.getQueue().size());
            metrics.setQueueCapacity(pool.getQueue().size() + pool.getQueue().remainingCapacity());
            metrics.setCompletedTasks(pool.getCompletedTaskCount());
            metrics.setTotalTasks(pool.getTaskCount());

            result.put(serviceName, metrics);
        });

        return result;
    }

    public static class PoolMetrics {
        private String serviceName;
        private int activeThreads;
        private int poolSize;
        private int corePoolSize;
        private int maxPoolSize;
        private int queueSize;
        private int queueCapacity;
        private long completedTasks;
        private long totalTasks;

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public int getActiveThreads() {
            return activeThreads;
        }

        public void setActiveThreads(int activeThreads) {
            this.activeThreads = activeThreads;
        }

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueSize() {
            return queueSize;
        }

        public void setQueueSize(int queueSize) {
            this.queueSize = queueSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public long getCompletedTasks() {
            return completedTasks;
        }

        public void setCompletedTasks(long completedTasks) {
            this.completedTasks = completedTasks;
        }

        public long getTotalTasks() {
            return totalTasks;
        }

        public void setTotalTasks(long totalTasks) {
            this.totalTasks = totalTasks;
        }
    }
}
