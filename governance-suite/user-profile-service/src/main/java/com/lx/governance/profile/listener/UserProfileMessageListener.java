package com.lx.governance.profile.listener;

import com.lx.governance.common.trace.Trace;
import com.lx.governance.profile.dto.*;
import com.lx.governance.profile.service.RedisProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileMessageListener {

    private final RedisProfileService redisProfileService;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = "user.profile.request")
    public void handleProfileRequest(UserProfileRequestMessage request, Message message) {
        String correlationId = request.getCorrelationId();
        String customerId = request.getCustomerId();
        String replyTo = message.getMessageProperties().getReplyTo();

        Trace.set(correlationId);

        log.info("[{}] 收到用户画像请求: customerId={}, replyTo={}", correlationId, customerId, replyTo);

        UserProfileResponseMessage response = new UserProfileResponseMessage();
        response.setCorrelationId(correlationId);
        response.setTimestamp(System.currentTimeMillis());

        try {
            UserProfileDTO profile = redisProfileService.getProfile(customerId);

            if (profile == null) {
                response.setSuccess(false);
                response.setErrorMessage("用户不存在");
            } else {
                response.setSuccess(true);
                response.setData(profile);
            }

        } catch (Exception e) {
            log.error("[{}] 构建用户画像失败: customerId={}", correlationId, customerId, e);
            response.setSuccess(false);
            response.setErrorMessage("构建用户画像失败: " + e.getMessage());
        }

        // Use replyTo if provided, otherwise fall back to dynamic routing key
        if (replyTo != null && !replyTo.isEmpty()) {
            // 必须设置 correlationId header，否则 RPC 客户端无法匹配响应
            rabbitTemplate.convertAndSend("", replyTo, response, msg -> {
                msg.getMessageProperties().setCorrelationId(correlationId);
                return msg;
            });
            log.info("[{}] 用户画像响应已发送到 replyTo 队列: {}, success={}", correlationId, replyTo, response.getSuccess());
        } else {
            String responseRoutingKey = "user.profile.response." + correlationId;
            rabbitTemplate.convertAndSend(
                "user.profile.exchange",
                responseRoutingKey,
                response,
                msg -> {
                    msg.getMessageProperties().setCorrelationId(correlationId);
                    return msg;
                }
            );
            log.info("[{}] 用户画像响应已发送到路由键: {}, success={}", correlationId, responseRoutingKey, response.getSuccess());
        }
    }
}
