package com.lx.governance.aichat.client;

import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.dto.UserProfileRequestMessage;
import com.lx.governance.aichat.dto.UserProfileResponseMessage;
import com.lx.governance.common.trace.Trace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 用户画像客户端 - 使用固定响应队列
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileClientFixed {

    private final RabbitTemplate rabbitTemplate;

    private static final String REQUEST_QUEUE = "user.profile.request";
    private static final String REPLY_QUEUE = "ai.chat.user.profile.reply";
    private static final long TIMEOUT_MS = 5000;

    // 存储待处理的请求
    private final Map<String, CompletableFuture<UserProfileResponseMessage>> pendingRequests = new ConcurrentHashMap<>();

    public CompletableFuture<UserProfileDTO> requestUserProfile(String customerId) {
        String correlationId = Trace.getOrCreate();
        long startTime = System.currentTimeMillis();

        log.info("Sending user profile request: customerId={}, correlationId={}", customerId, correlationId);

        UserProfileRequestMessage request = new UserProfileRequestMessage();
        request.setCustomerId(customerId);
        request.setCorrelationId(correlationId);
        request.setTimestamp(System.currentTimeMillis());

        // 创建 CompletableFuture 并存储
        CompletableFuture<UserProfileResponseMessage> future = new CompletableFuture<>();
        pendingRequests.put(correlationId, future);

        // 发送请求，指定固定的 replyTo 队列
        rabbitTemplate.convertAndSend(REQUEST_QUEUE, request, message -> {
            message.getMessageProperties().setReplyTo(REPLY_QUEUE);
            message.getMessageProperties().setCorrelationId(correlationId);
            return message;
        });

        // 设置超时处理
        return future.orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .handle((response, ex) -> {
                    pendingRequests.remove(correlationId);
                    long duration = System.currentTimeMillis() - startTime;

                    if (ex != null) {
                        log.error("User profile request timeout: customerId={}, timeout={}ms, duration={}ms",
                                customerId, TIMEOUT_MS, duration);
                        throw new RuntimeException("User profile request timeout after " + TIMEOUT_MS + "ms");
                    }

                    if (!response.getSuccess()) {
                        log.error("User profile request failed: customerId={}, error={}, duration={}ms",
                                customerId, response.getErrorMessage(), duration);
                        throw new RuntimeException("Failed to request user profile: " + response.getErrorMessage());
                    }

                    log.info("User profile request succeeded: customerId={}, duration={}ms", customerId, duration);
                    return response.getData();
                });
    }

    /**
     * 监听固定的响应队列
     */
    @RabbitListener(queues = REPLY_QUEUE)
    public void handleResponse(UserProfileResponseMessage response, Message message) {
        String correlationId = message.getMessageProperties().getCorrelationId();
        log.info("Received user profile response: correlationId={}, success={}", correlationId, response.getSuccess());

        CompletableFuture<UserProfileResponseMessage> future = pendingRequests.remove(correlationId);
        if (future != null) {
            future.complete(response);
        } else {
            log.warn("No pending request found for correlationId: {}", correlationId);
        }
    }
}
