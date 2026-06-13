package com.lx.governance.order.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
@EnableConfigurationProperties(ThreadPoolProperties.class)
public class ThreadPoolConfig implements DisposableBean {

    private final ThreadPoolProperties properties;
    private ThreadPoolTaskExecutor ioExecutor;
    private ThreadPoolTaskExecutor cpuExecutor;
    private ThreadPoolTaskExecutor bizExecutor;
    private ThreadPoolTaskScheduler scheduledExecutor;

    public ThreadPoolConfig(ThreadPoolProperties properties) {
        this.properties = properties;
    }

    @Bean("ioExecutor")
    public ThreadPoolTaskExecutor ioExecutor() {
        this.ioExecutor = buildExecutor(properties.getIo(), "CallerRuns");
        return this.ioExecutor;
    }

    @Bean("cpuExecutor")
    public ThreadPoolTaskExecutor cpuExecutor() {
        // CPU 密集型更适合快速失败，避免无限堆积
        this.cpuExecutor = buildExecutor(properties.getCpu(), "Abort");
        return this.cpuExecutor;
    }

    @Bean("bizExecutor")
    public ThreadPoolTaskExecutor bizExecutor() {
        this.bizExecutor = buildExecutor(properties.getBiz(), "CallerRuns");
        return this.bizExecutor;
    }

    @Bean("scheduledExecutor")
    public ThreadPoolTaskScheduler scheduledExecutor() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.getScheduled().getPoolSize());
        scheduler.setThreadNamePrefix(properties.getScheduled().getThreadNamePrefix());
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(60);
        scheduler.setErrorHandler(t -> log.error("scheduled task execute error", t));
        scheduler.initialize();
        this.scheduledExecutor = scheduler;
        return scheduler;
    }

    private ThreadPoolTaskExecutor buildExecutor(ThreadPoolProperties.PoolConfig config, String rejectPolicy) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.getCoreSize());
        executor.setMaxPoolSize(config.getMaxSize());
        executor.setQueueCapacity(config.getQueueCapacity());
        executor.setKeepAliveSeconds(config.getKeepAliveSeconds());
        executor.setThreadNamePrefix(config.getThreadNamePrefix());

        // 允许核心线程超时，可按需打开
        executor.setAllowCoreThreadTimeOut(false);

        // 关闭时等待任务执行完成
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        // 透传 MDC
        executor.setTaskDecorator(new MdcTaskDecorator());

        // 拒绝策略
        if ("Abort".equalsIgnoreCase(rejectPolicy)) {
            executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        } else {
            executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        }

        executor.setThreadFactory(r -> {
            Thread thread = new Thread(r);
            thread.setDaemon(false);
            return thread;
        });

        executor.initialize();
        return executor;
    }

    @Override
    public void destroy() {
        if (ioExecutor != null) {
            ioExecutor.shutdown();
        }
        if (cpuExecutor != null) {
            cpuExecutor.shutdown();
        }
        if (bizExecutor != null) {
            bizExecutor.shutdown();
        }
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
        }
    }
}
