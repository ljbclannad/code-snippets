package com.lx.governance.common.bulkhead;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class BulkheadThreadPoolManager implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(BulkheadThreadPoolManager.class);

    private final Map<String, ThreadPoolExecutor> poolCache = new ConcurrentHashMap<>();
    private final BulkheadProperties properties;

    public BulkheadThreadPoolManager(BulkheadProperties properties) {
        this.properties = properties;
    }

    public ThreadPoolExecutor getOrCreatePool(String serviceName) {
        return poolCache.computeIfAbsent(serviceName, this::createPool);
    }

    public Map<String, ThreadPoolExecutor> getAllPools() {
        return poolCache;
    }

    private ThreadPoolExecutor createPool(String serviceName) {
        BulkheadProperties.PoolConfig config = properties.getConfigForService(serviceName);

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            config.getCorePoolSize(),
            config.getMaxPoolSize(),
            config.getKeepAliveSeconds(),
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(config.getQueueCapacity()),
            new BulkheadThreadFactory(serviceName),
            new ThreadPoolExecutor.AbortPolicy()
        );

        log.info("Created bulkhead thread pool for service: {}, config: {}", serviceName, config);

        return pool;
    }

    @Override
    public void destroy() {
        log.info("Shutting down bulkhead thread pools...");
        poolCache.forEach((serviceName, pool) -> {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("Thread pool for {} did not terminate in time, forcing shutdown", serviceName);
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.error("Interrupted while shutting down thread pool for {}", serviceName, e);
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
        log.info("All bulkhead thread pools shut down");
    }

    private static class BulkheadThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String serviceName;

        BulkheadThreadFactory(String serviceName) {
            this.serviceName = serviceName;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r,
                "bulkhead-" + serviceName + "-thread-" + threadNumber.getAndIncrement());
            t.setDaemon(false);
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        }
    }
}
