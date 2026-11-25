package com.lx.governance.aichat.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.lx.governance.aichat.exception.ConversationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ConversationExecutionGuard {

    private final ConcurrentHashMap<String, ConversationLock> conversationLocks = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public ConversationExecutionGuard(
            @Value("${ai-chat.concurrency.conversation-lock-timeout-ms:0}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public <T> CompletableFuture<T> tryExecute(String conversationId, Supplier<CompletableFuture<T>> operation) {
        ConversationLock lock = conversationLocks.compute(conversationId, (key, existing) -> {
            ConversationLock entry = existing == null ? new ConversationLock() : existing;
            entry.references++;
            return entry;
        });
        if (!acquire(lock.semaphore)) {
            releaseReference(conversationId);
            return CompletableFuture.failedFuture(ConversationException.busy(conversationId));
        }

        // 将释放动作挂在实际任务上，调用方取消结果时会话仍由在途任务持有。
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            operation.get().whenComplete((value, failure) -> {
                release(conversationId, lock);
                if (failure == null) {
                    result.complete(value);
                } else {
                    result.completeExceptionally(failure);
                }
            });
        } catch (Throwable throwable) {
            release(conversationId, lock);
            result.completeExceptionally(throwable);
        }
        return result;
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

    private void release(String conversationId, ConversationLock lock) {
        lock.semaphore.release();
        releaseReference(conversationId);
    }

    private void releaseReference(String conversationId) {
        // 获取锁引用和删除空闲锁都按会话键原子执行，计数包含尚未开始等待的请求。
        conversationLocks.computeIfPresent(conversationId, (key, entry) -> --entry.references == 0 ? null : entry);
    }

    private static final class ConversationLock {
        private final Semaphore semaphore = new Semaphore(1);
        private int references;
    }
}
