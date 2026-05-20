package com.lx.governance.order.config.listener;

import com.lx.governance.order.config.properties.ThreadPoolProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ThreadPoolConfigChangeListener {

    private final ThreadPoolProperties threadPoolProperties;
    private final ThreadPoolTaskExecutor ioExecutor;

    public ThreadPoolConfigChangeListener(
            ThreadPoolProperties threadPoolProperties,
            @Qualifier("ioExecutor") ThreadPoolTaskExecutor ioExecutor) {
        this.threadPoolProperties = threadPoolProperties;
        this.ioExecutor = ioExecutor;
    }

    @EventListener
    public void onRefresh(RefreshScopeRefreshedEvent event) {
        int newCoreSize = threadPoolProperties.getCoreSize();
        int newMaxSize = threadPoolProperties.getMaxSize();

        log.info("线程池配置变更: coreSize={}, maxSize={}", newCoreSize, newMaxSize);

        ioExecutor.setCorePoolSize(newCoreSize);
        ioExecutor.setMaxPoolSize(newMaxSize);

        log.info("线程池已调整: active={}, poolSize={}, queueSize={}",
            ioExecutor.getActiveCount(),
            ioExecutor.getPoolSize(),
            ioExecutor.getThreadPoolExecutor().getQueue().size());
    }
}
