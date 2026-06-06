package com.lx.governance.appointment.service;

import com.lx.governance.appointment.dto.GrabResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;

@Slf4j
@Service
public class AppointmentService {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<String> grabLuaScript;
    private final IdempotencyService idempotencyService;
    private final com.lx.governance.appointment.websocket.AppointmentPushService appointmentPushService;

    public AppointmentService(
            StringRedisTemplate redisTemplate,
            RedisScript<String> grabLuaScript,
            IdempotencyService idempotencyService,
            com.lx.governance.appointment.websocket.AppointmentPushService appointmentPushService) {
        this.redisTemplate = redisTemplate;
        this.grabLuaScript = grabLuaScript;
        this.idempotencyService = idempotencyService;
        this.appointmentPushService = appointmentPushService;
    }

    public GrabResultVO grabAppointment(Long userId, Long scheduleId, String requestId) {
        // 1. 幂等检查
        String idempotencyKey = "idempotency:appointment:" + requestId;
        if (!idempotencyService.tryLock(idempotencyKey, Duration.ofHours(1))) {
            log.warn("[APPOINTMENT] [GRAB] 重复请求: userId={}, scheduleId={}, requestId={}",
                userId, scheduleId, requestId);
            throw new IllegalArgumentException("重复请求");
        }

        // 2. 执行 Redis Lua 脚本
        String result = redisTemplate.execute(
            grabLuaScript,
            Arrays.asList(
                "appointment:stock:" + scheduleId,
                "appointment:buyers:" + scheduleId,
                "appointment:stream:orders"
            ),
            userId.toString(),
            scheduleId.toString(),
            requestId,
            String.valueOf(System.currentTimeMillis())
        );

        // 3. 解析结果
        if ("SOLD_OUT".equals(result)) {
            log.warn("[APPOINTMENT] [GRAB] 号源已抢完: userId={}, scheduleId={}", userId, scheduleId);
            throw new IllegalStateException("号源已抢完");
        }

        if ("ALREADY_GRABBED".equals(result)) {
            log.warn("[APPOINTMENT] [GRAB] 用户已抢过: userId={}, scheduleId={}", userId, scheduleId);
            throw new IllegalStateException("您已抢过该排班");
        }

        // 4. 返回成功
        GrabResultVO vo = new GrabResultVO();
        vo.setScheduleId(scheduleId);
        vo.setExpireAt(LocalDateTime.now().plusMinutes(15));
        vo.setRequestId(requestId);

        log.info("[APPOINTMENT] [GRAB] 抢号成功: userId={}, scheduleId={}, requestId={}",
            userId, scheduleId, requestId);
        appointmentPushService.pushGrabAccepted(userId, scheduleId, requestId, vo);

        return vo;
    }
}
