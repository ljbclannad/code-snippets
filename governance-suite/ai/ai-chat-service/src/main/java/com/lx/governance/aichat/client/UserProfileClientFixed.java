package com.lx.governance.aichat.client;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.dto.UserProfileRequestMessage;
import com.lx.governance.aichat.dto.UserProfileResponseMessage;
import com.lx.governance.common.trace.Trace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

    /**
     * RabbitMQ RPC 异步调用流程：
     *
     * <pre>
     * 调用方线程                    RabbitMQ                    服务端线程                    监听器线程
     *     |                           |                           |                           |
     *     |--① 创建 Future ---------->|                           |                           |
     *     |   存入 pendingRequests    |                           |                           |
     *     |                           |                           |                           |
     *     |--② 发送消息 ------------->|                           |                           |
     *     |   (replyTo + correlationId)|                          |                           |
     *     |                           |                           |                           |
     *     |--③ 返回 Future ---------->|                           |                           |
     *     |   (立即返回，无数据)        |                           |                           |
     *     |                           |                           |                           |
     *     |                           |--④ 路由到服务端 -------->|                           |
     *     |                           |                           |                           |
     *     |                           |                           |--⑤ 处理业务逻辑          |
     *     |                           |                           |   查询用户画像            |
     *     |                           |                           |                           |
     *     |                           |<--⑥ 发送响应到 replyTo --|                           |
     *     |                           |   (携带 correlationId)    |                           |
     *     |                           |                           |                           |
     *     |                           |--⑦ 路由到监听器 ---------------------------------->|
     *     |                           |                           |                           |
     *     |                           |                           |                           |--⑧ 提取 correlationId
     *     |                           |                           |                           |   从 Map 获取 Future
     *     |                           |                           |                           |
     *     |                           |                           |                           |--⑨ future.complete()
     *     |                           |                           |                           |
     *     |<--⑩ join/get 获取结果 ----------------------------------------------------|
     *     |   (阻塞等待直到 Future 完成)
     *
     * 关键点：
     * - correlationId：匹配异步请求和响应
     * - replyTo：指定响应队列（固定队列，所有请求共用）
     * - CompletableFuture：实现异步等待，支持超时控制
     * </pre>
     */
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

    public int getPendingRequestCount() {
        return pendingRequests.size();
    }
}
