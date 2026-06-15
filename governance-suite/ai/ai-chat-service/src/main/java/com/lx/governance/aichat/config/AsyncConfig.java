package com.lx.governance.aichat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步配置类
 *
 * 这里拆分多个单例线程池，核心目的是做线程资源隔离：
 * 1. 聊天主链路与记忆提取这类边路异步任务隔离，避免慢任务或堆积任务拖垮主对话请求。
 * 2. 不同任务可以按各自特性独立设置并发度、队列长度和线程名前缀，便于调优与排障。
 * 3. 后续做降级时，也可以按线程池维度限制某一类能力，而不是让整个服务一起受影响。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 聊天主链路线程池。
     *
     * 当前代码里还没有真正接入这个线程池；
     * 它更像是为后续将聊天主处理流程显式切到独立线程池而预留。
     */
    @Bean(name = "chatExecutor")
    public Executor chatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("chat-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * 记忆提取及聊天后置处理线程池。
     *
     * 这类任务不在用户主对话链路上，单独隔离后可以降低对主请求 RT 和可用性的影响。
     */
    @Bean(name = "memoryExtractExecutor")
    public Executor memoryExtractExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("memory-extract-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
