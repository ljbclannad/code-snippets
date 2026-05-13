package com.lx.governance.vectormemory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步配置
 *
 * 配置异步任务执行器，用于记忆提取等异步操作
 */
@Configuration
public class AsyncConfig {

    /**
     * 记忆提取异步执行器
     *
     * @return 线程池执行器
     */
    @Bean(name = "memoryExtractionExecutor")
    public Executor memoryExtractionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 核心线程数
        executor.setCorePoolSize(5);

        // 最大线程数
        executor.setMaxPoolSize(10);

        // 队列容量
        executor.setQueueCapacity(100);

        // 线程名称前缀
        executor.setThreadNamePrefix("memory-extraction-");

        // 等待任务完成后再关闭
        executor.setWaitForTasksToCompleteOnShutdown(true);

        // 等待终止的秒数
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();
        return executor;
    }
}
