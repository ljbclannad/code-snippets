package com.lx.governance.vectormemory.task;

import com.lx.governance.vectormemory.domain.DeadLetterMessage;
import com.lx.governance.vectormemory.application.governance.DeadLetterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 死信消息自动重试任务
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DeadLetterRetryTask {

    private final DeadLetterService deadLetterService;

    /**
     * 每5分钟检查一次待处理的死信消息并尝试重试
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    public void retryPendingMessages() {
        log.info("开始自动重试死信消息");

        List<DeadLetterMessage> pendingMessages = deadLetterService.findPendingMessages(50);
        if (pendingMessages.isEmpty()) {
            log.info("没有待重试的死信消息");
            return;
        }

        int successCount = 0;
        int failCount = 0;

        for (DeadLetterMessage message : pendingMessages) {
            try {
                // 使用指数退避策略
                long delayMs = calculateBackoffDelay(message.getRetryCount());
                if (System.currentTimeMillis() - message.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() < delayMs) {
                    continue; // 还未到重试时间
                }

                boolean success = deadLetterService.retryDeadLetter(message.getId());
                if (success) {
                    successCount++;
                } else {
                    failCount++;
                }
            } catch (Exception e) {
                log.error("重试死信消息失败: id={}", message.getId(), e);
                failCount++;
            }
        }

        log.info("死信消息自动重试完成: 成功={}, 失败={}", successCount, failCount);
    }

    /**
     * 计算指数退避延迟时间
     * 第1次: 1分钟
     * 第2次: 5分钟
     * 第3次: 15分钟
     */
    private long calculateBackoffDelay(int retryCount) {
        return (long) Math.pow(5, retryCount) * 60 * 1000;
    }
}
