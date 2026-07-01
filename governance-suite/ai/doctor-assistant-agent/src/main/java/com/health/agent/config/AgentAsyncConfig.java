package com.health.agent.config;

import com.health.agent.metrics.ToolMetrics;
import com.health.agent.metrics.AgentMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class AgentAsyncConfig {

    @Bean(name = "agentStreamExecutor", destroyMethod = "shutdownNow")
    public ThreadPoolExecutor agentStreamExecutor(
        @Value("${agent.sse.executor.core-pool-size:4}") int corePoolSize,
        @Value("${agent.sse.executor.max-pool-size:8}") int maxPoolSize,
        @Value("${agent.sse.executor.queue-capacity:64}") int queueCapacity,
        @Value("${agent.sse.executor.keep-alive-millis:60000}") long keepAliveMillis,
        AgentMetrics agentMetrics
    ) {
        return buildBoundedExecutor(
            "agent-stream-executor",
            corePoolSize,
            maxPoolSize,
            queueCapacity,
            keepAliveMillis,
            "agent-stream-",
            agentMetrics
        );
    }

    @Bean(name = "patientTaskStreamExecutor", destroyMethod = "shutdownNow")
    public ThreadPoolExecutor patientTaskStreamExecutor(
        @Value("${agent.patient-task-stream.executor.core-pool-size:2}") int corePoolSize,
        @Value("${agent.patient-task-stream.executor.max-pool-size:4}") int maxPoolSize,
        @Value("${agent.patient-task-stream.executor.queue-capacity:32}") int queueCapacity,
        @Value("${agent.patient-task-stream.executor.keep-alive-millis:60000}") long keepAliveMillis,
        AgentMetrics agentMetrics
    ) {
        return buildBoundedExecutor(
            "patient-task-stream-executor",
            corePoolSize,
            maxPoolSize,
            queueCapacity,
            keepAliveMillis,
            "patient-task-stream-",
            agentMetrics
        );
    }

    @Bean(name = "deepSeekRequestExecutor", destroyMethod = "shutdownNow")
    public ThreadPoolExecutor deepSeekRequestExecutor(
        @Value("${agent.deepseek.executor.core-pool-size:4}") int corePoolSize,
        @Value("${agent.deepseek.executor.max-pool-size:8}") int maxPoolSize,
        @Value("${agent.deepseek.executor.queue-capacity:32}") int queueCapacity,
        @Value("${agent.deepseek.executor.keep-alive-millis:60000}") long keepAliveMillis,
        AgentMetrics agentMetrics
    ) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            keepAliveMillis,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            new NamedThreadFactory("deepseek-request-"),
            (runnable, threadPoolExecutor) -> {
                agentMetrics.recordExecutorRejected("deepseek-request-executor");
                throw new RejectedExecutionException("DeepSeek 请求线程池已饱和");
            }
        );
        executor.allowCoreThreadTimeOut(true);
        agentMetrics.bindExecutor("deepseek-request-executor", executor);
        return executor;
    }

    @Bean(name = "agentToolExecutor", destroyMethod = "shutdownNow")
    public ThreadPoolExecutor agentToolExecutor(
        @Value("${agent.tool-executor.core-pool-size:8}") int corePoolSize,
        @Value("${agent.tool-executor.max-pool-size:16}") int maxPoolSize,
        @Value("${agent.tool-executor.queue-capacity:64}") int queueCapacity,
        @Value("${agent.tool-executor.keep-alive-millis:60000}") long keepAliveMillis,
        ToolMetrics toolMetrics
    ) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            keepAliveMillis,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            new NamedThreadFactory("agent-tool-executor-"),
            (runnable, threadPoolExecutor) -> {
                toolMetrics.recordToolExecutorRejected("agent-tool-executor");
                throw new RejectedExecutionException("工具执行线程池已饱和");
            }
        );
        executor.allowCoreThreadTimeOut(true);
        toolMetrics.bindToolExecutor("agent-tool-executor", executor);
        return executor;
    }

    private ThreadPoolExecutor buildBoundedExecutor(
        String executorName,
        int corePoolSize,
        int maxPoolSize,
        int queueCapacity,
        long keepAliveMillis,
        String threadNamePrefix,
        AgentMetrics agentMetrics
    ) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            keepAliveMillis,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            new NamedThreadFactory(threadNamePrefix),
            (runnable, threadPoolExecutor) -> {
                agentMetrics.recordExecutorRejected(executorName);
                throw new RejectedExecutionException(executorName + " 已饱和");
            }
        );
        executor.allowCoreThreadTimeOut(true);
        agentMetrics.bindExecutor(executorName, executor);
        return executor;
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private int index = 0;

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + index++);
            thread.setDaemon(true);
            return thread;
        }
    }
}
