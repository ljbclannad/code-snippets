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

    private final ConcurrentHashMap<String, Semaphore> conversationSemaphores = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public ConversationExecutionGuard(
            @Value("${ai-chat.concurrency.conversation-lock-timeout-ms:0}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public <T> CompletableFuture<T> tryExecute(String conversationId, Supplier<CompletableFuture<T>> operation) {
        Semaphore semaphore = conversationSemaphores.computeIfAbsent(conversationId, key -> new Semaphore(1));
        boolean acquired = acquire(semaphore);
        if (!acquired) {
            return CompletableFuture.failedFuture(
                    new ConversationBusyException("当前会话正在处理中，请稍后再试"));
        }

        try {
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
        semaphore.release();
        if (semaphore.availablePermits() == 1 && !semaphore.hasQueuedThreads()) {
            conversationSemaphores.remove(conversationId, semaphore);
        }
    }
}
