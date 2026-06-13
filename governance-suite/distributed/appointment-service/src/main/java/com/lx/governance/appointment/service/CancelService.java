package com.lx.governance.appointment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.governance.appointment.entity.AppointmentOrder;
import com.lx.governance.appointment.entity.QuotaRefundLog;
import com.lx.governance.appointment.mapper.AppointmentOrderMapper;
import com.lx.governance.appointment.mapper.QuotaRefundLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

@Slf4j
@Service
public class CancelService {

    private static final String STATUS_RESERVED = "RESERVED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String REFUND_TYPE_CANCEL = "CANCEL";
    private static final String DEFAULT_CANCEL_REASON = "用户主动取消";
    private static final String REFUND_STATUS_SUCCESS = "REFUNDED";
    private static final String REFUND_STATUS_NOT_FOUND = "NOT_FOUND";
    private static final String IDEMPOTENCY_KEY_PREFIX = "idem:refund:";
    private static final String LOG_STATUS_SUCCESS = "SUCCESS";
    private static final String LOG_STATUS_FAILED = "FAILED";

    private final AppointmentOrderMapper orderMapper;
    private final QuotaRefundLogMapper refundLogMapper;
    private final IdempotencyService idempotencyService;
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<String> refundLuaScript;

    public CancelService(
            AppointmentOrderMapper orderMapper,
            QuotaRefundLogMapper refundLogMapper,
            IdempotencyService idempotencyService,
            StringRedisTemplate redisTemplate,
            RedisScript<String> refundLuaScript) {
        this.orderMapper = orderMapper;
        this.refundLogMapper = refundLogMapper;
        this.idempotencyService = idempotencyService;
        this.redisTemplate = redisTemplate;
        this.refundLuaScript = refundLuaScript;
    }

    /**
     * 取消订单
     *
     * @param userId  用户ID
     * @param orderNo 订单号
     * @param reason  取消原因
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long userId, String orderNo, String reason) {
        String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + orderNo;
        boolean completed = false;

        // 1. 查询订单
        AppointmentOrder order = orderMapper.selectOne(
                new LambdaQueryWrapper<AppointmentOrder>()
                        .eq(AppointmentOrder::getOrderNo, orderNo)
        );

        // 2. 校验订单存在
        if (order == null) {
            throw new IllegalArgumentException("订单不存在");
        }

        // 3. 校验用户权限
        if (!Objects.equals(order.getUserId(), userId)) {
            throw new IllegalArgumentException("无权操作该订单");
        }

        // 4. 校验订单状态（只能取消 RESERVED 状态的订单）
        if (!STATUS_RESERVED.equals(order.getStatus())) {
            throw new IllegalStateException("订单状态不正确，当前状态: " + order.getStatus());
        }

        // 5. 回补幂等检查
        if (!idempotencyService.tryLock(idempotencyKey, Duration.ofHours(1))) {
            log.info("[APPOINTMENT] [CANCEL] 重复取消请求: userId={}, orderNo={}", userId, orderNo);
            return; // 幂等返回
        }

        try {
            // 6. 更新订单状态为 CANCELLED（使用乐观锁）
            LocalDateTime cancelledAt = LocalDateTime.now();
            AppointmentOrder updateOrder = new AppointmentOrder();
            updateOrder.setId(order.getId());
            updateOrder.setStatus(STATUS_CANCELLED);
            updateOrder.setCancelledAt(cancelledAt);
            updateOrder.setCancelReason(reason != null ? reason : DEFAULT_CANCEL_REASON);
            updateOrder.setVersion(order.getVersion());

            int updated = orderMapper.updateById(updateOrder);
            if (updated == 0) {
                // 检查订单是否已取消
                AppointmentOrder latestOrder = orderMapper.selectById(order.getId());
                if (latestOrder != null && STATUS_CANCELLED.equals(latestOrder.getStatus())) {
                    log.info("[APPOINTMENT] [CANCEL] 订单已取消，幂等返回: orderNo={}", orderNo);
                    completed = true;
                    return;
                }
                throw new IllegalStateException("订单更新失败，版本冲突且订单未取消");
            }

            // 7. 执行 Redis Lua 回补脚本
            String result = redisTemplate.execute(
                    refundLuaScript,
                    Arrays.asList(
                            "appointment:stock:" + order.getScheduleId(),
                            "appointment:buyers:" + order.getScheduleId()
                    ),
                    userId.toString()
            );

            // 8. 检查回补结果
            if (!REFUND_STATUS_SUCCESS.equals(result)) {
                log.error("[APPOINTMENT] [CANCEL] 配额回补失败: userId={}, orderNo={}, result={}",
                    userId, orderNo, result);
                throw new IllegalStateException("配额回补失败: " + result);
            }

            // 9. 记录回补日志
            QuotaRefundLog refundLog = new QuotaRefundLog();
            refundLog.setScheduleId(order.getScheduleId());
            refundLog.setOrderNo(orderNo);
            refundLog.setRefundType(REFUND_TYPE_CANCEL);
            refundLog.setRefundedAt(cancelledAt);
            refundLog.setStatus(LOG_STATUS_SUCCESS);
            refundLogMapper.insert(refundLog);

            log.info("[APPOINTMENT] [CANCEL] 取消成功: userId={}, orderNo={}, luaResult={}",
                    userId, orderNo, result);
            completed = true;
        } finally {
            if (!completed) {
                idempotencyService.unlock(idempotencyKey);
            }
        }
    }
}
