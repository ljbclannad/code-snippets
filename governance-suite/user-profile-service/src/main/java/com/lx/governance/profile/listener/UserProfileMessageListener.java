package com.lx.governance.profile.listener;

import com.lx.governance.common.trace.Trace;
import com.lx.governance.profile.dto.*;
import com.lx.governance.profile.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileMessageListener {

    private final UserProfileService userProfileService;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = "user.profile.request")
    public void handleProfileRequest(UserProfileRequestMessage request) {
        String correlationId = request.getCorrelationId();
        String customerId = request.getCustomerId();

        Trace.set(correlationId);

        log.info("[{}] 收到用户画像请求: customerId={}", correlationId, customerId);

        UserProfileResponseMessage response = new UserProfileResponseMessage();
        response.setCorrelationId(correlationId);
        response.setTimestamp(System.currentTimeMillis());

        try {
            UserProfileDTO profile = userProfileService.buildUserProfile(customerId);

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

        String responseRoutingKey = "user.profile.response." + correlationId;
        rabbitTemplate.convertAndSend(
            "user.profile.exchange",
            responseRoutingKey,
            response
        );

        log.info("[{}] 用户画像响应已发送: success={}", correlationId, response.getSuccess());
    }
}
