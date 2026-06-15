package com.lx.governance.aichat.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ConversationExecutionGuard {

    /**
     * 按 conversationId 维护独立信号量。
     *
     * 这里不是全局串行，而是“同一会话串行、不同会话并行”：
     * - 相同 conversationId 的请求共享同一个 Semaphore(1)
     * - 不同 conversationId 的请求各自持有不同的 Semaphore，因此互不阻塞
     */
    private final ConcurrentHashMap<String, Semaphore> conversationSemaphores = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public ConversationExecutionGuard(
            @Value("${ai-chat.concurrency.conversation-lock-timeout-ms:0}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public <T> CompletableFuture<T> tryExecute(String conversationId, Supplier<CompletableFuture<T>> operation) {
        // 为当前会话获取一把“单许可锁”。
        // computeIfAbsent 保证同一个 conversationId 复用同一个 Semaphore。
        Semaphore semaphore = conversationSemaphores.computeIfAbsent(conversationId, key -> new Semaphore(1));
        boolean acquired = acquire(semaphore);
        if (!acquired) {
            return CompletableFuture.failedFuture(
                    new ConversationBusyException("当前会话正在处理中，请稍后再试"));
        }

        try {
            // 这里必须在异步任务真正完成时再释放锁；
            // 如果在方法返回 CompletableFuture 时立刻释放，会导致同一会话重新并发进入。
            return operation.get().whenComplete((result, throwable) -> release(conversationId, semaphore));
        } catch (Throwable throwable) {
            release(conversationId, semaphore);
            return CompletableFuture.failedFuture(throwable);
        }
    }

    private boolean acquire(Semaphore semaphore) {
        try {
            if (timeoutMs <= 0) {
                return semaphore.tryAcquire();
            }
            return semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void release(String conversationId, Semaphore semaphore) {
        // 释放当前 conversationId 对应的许可，让同一会话的后续请求可以继续竞争执行。
        semaphore.release();

        // 只有当这把锁已经完全空闲，且没有线程在等待它时，才从 map 中移除。
        // 这样做是为了避免 conversationSemaphores 随会话数无限增长。
        if (semaphore.availablePermits() == 1 && !semaphore.hasQueuedThreads()) {
            conversationSemaphores.remove(conversationId, semaphore);
        }
    }
}
