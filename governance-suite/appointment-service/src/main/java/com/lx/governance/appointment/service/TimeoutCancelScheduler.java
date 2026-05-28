package com.lx.governance.appointment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.governance.appointment.entity.AppointmentOrder;
import com.lx.governance.appointment.entity.QuotaRefundLog;
import com.lx.governance.appointment.mapper.AppointmentOrderMapper;
import com.lx.governance.appointment.mapper.QuotaRefundLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 超时订单自动取消定时任务
 */
@Slf4j
@Service
public class TimeoutCancelScheduler {

    private static final int TIMEOUT_MINUTES = 15;
    private static final int BATCH_SIZE = 100;
    private static final String STATUS_RESERVED = "RESERVED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String REFUND_TYPE_TIMEOUT = "TIMEOUT";
    private static final String REFUND_STATUS_SUCCESS = "REFUNDED";
    private static final String IDEMPOTENCY_KEY_PREFIX = "idem:refund:";
    private static final String LOG_STATUS_SUCCESS = "SUCCESS";
    private static final String TIMEOUT_CANCEL_REASON = "订单超时自动取消";

    private final AppointmentOrderMapper orderMapper;
    private final QuotaRefundLogMapper refundLogMapper;
    private final IdempotencyService idempotencyService;
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<String> refundLuaScript;

    public TimeoutCancelScheduler(
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
     * 定时扫描并取消超时订单
     * 每分钟执行一次
     */
    @Scheduled(cron = "0 * * * * ?")
    public void cancelTimeoutOrders() {
        try {
            // 1. 查询超时订单（RESERVED 且 reserved_at < NOW() - 15分钟）
            LocalDateTime timeoutThreshold = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);

            List<AppointmentOrder> timeoutOrders = orderMapper.selectList(
                    new LambdaQueryWrapper<AppointmentOrder>()
                            .eq(AppointmentOrder::getStatus, STATUS_RESERVED)
                            .lt(AppointmentOrder::getReservedAt, timeoutThreshold)
                            .last("LIMIT " + BATCH_SIZE)
            );

            if (timeoutOrders.isEmpty()) {
                return;
            }

            log.info("[APPOINTMENT] [TIMEOUT] 发现超时订单: count={}", timeoutOrders.size());

            // 2. 批量处理超时订单
            int successCount = 0;
            int failCount = 0;

            for (AppointmentOrder order : timeoutOrders) {
                try {
                    processTimeoutOrder(order);
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                    log.error("[APPOINTMENT] [TIMEOUT] 处理超时订单失败: orderNo={}",
                            order.getOrderNo(), e);
                }
            }

            log.info("[APPOINTMENT] [TIMEOUT] 批量处理完成: total={}, success={}, fail={}",
                    timeoutOrders.size(), successCount, failCount);

        } catch (Exception e) {
            log.error("[APPOINTMENT] [TIMEOUT] 定时任务执行失败", e);
        }
    }

    /**
     * 处理单个超时订单
     *
     * @param order 超时订单
     */
    @Transactional(rollbackFor = Exception.class)
    public void processTimeoutOrder(AppointmentOrder order) {
        String orderNo = order.getOrderNo();
        Long userId = order.getUserId();
        String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + orderNo;
        boolean completed = false;

        // 1. 回补幂等检查
        if (!idempotencyService.tryLock(idempotencyKey, Duration.ofHours(1))) {
            log.info("[APPOINTMENT] [TIMEOUT] 订单已处理，跳过: orderNo={}", orderNo);
            return;
        }

        try {
            // 2. 更新订单状态为 CANCELLED（使用乐观锁）
            LocalDateTime cancelledAt = LocalDateTime.now();
            AppointmentOrder updateOrder = new AppointmentOrder();
            updateOrder.setId(order.getId());
            updateOrder.setStatus(STATUS_CANCELLED);
            updateOrder.setCancelledAt(cancelledAt);
            updateOrder.setCancelReason(TIMEOUT_CANCEL_REASON);
            updateOrder.setVersion(order.getVersion());

            int updated = orderMapper.updateById(updateOrder);
            if (updated == 0) {
                // 检查订单是否已取消
                AppointmentOrder latestOrder = orderMapper.selectById(order.getId());
                if (latestOrder != null && STATUS_CANCELLED.equals(latestOrder.getStatus())) {
                    log.info("[APPOINTMENT] [TIMEOUT] 订单已取消，幂等返回: orderNo={}", orderNo);
                    completed = true;
                    return;
                }
                throw new IllegalStateException("订单更新失败，版本冲突且订单未取消");
            }

            // 3. 执行 Redis Lua 回补脚本
            String result = redisTemplate.execute(
                    refundLuaScript,
                    Arrays.asList(
                            "appointment:stock:" + order.getScheduleId(),
                            "appointment:buyers:" + order.getScheduleId()
                    ),
                    userId.toString()
            );

            // 4. 检查回补结果
            if (!REFUND_STATUS_SUCCESS.equals(result)) {
                log.error("[APPOINTMENT] [TIMEOUT] 配额回补失败: orderNo={}, result={}", orderNo, result);
                throw new IllegalStateException("配额回补失败: " + result);
            }

            // 5. 记录回补日志
            QuotaRefundLog refundLog = new QuotaRefundLog();
            refundLog.setScheduleId(order.getScheduleId());
            refundLog.setOrderNo(orderNo);
            refundLog.setRefundType(REFUND_TYPE_TIMEOUT);
            refundLog.setRefundedAt(cancelledAt);
            refundLog.setStatus(LOG_STATUS_SUCCESS);
            refundLogMapper.insert(refundLog);

            log.info("[APPOINTMENT] [TIMEOUT] 超时订单取消成功: orderNo={}, userId={}", orderNo, userId);
            completed = true;
        } finally {
            if (!completed) {
                idempotencyService.unlock(idempotencyKey);
            }
        }
    }
}
