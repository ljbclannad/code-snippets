package com.lx.governance.appointment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.governance.appointment.entity.AppointmentOrder;
import com.lx.governance.appointment.mapper.AppointmentOrderMapper;
import com.lx.governance.common.id.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@Slf4j
public class PaymentService {

    private final AppointmentOrderMapper orderMapper;
    private final IdempotencyService idempotencyService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final JdbcTemplate jdbcTemplate;

    public PaymentService(AppointmentOrderMapper orderMapper,
                          IdempotencyService idempotencyService,
                          SnowflakeIdGenerator snowflakeIdGenerator,
                          JdbcTemplate jdbcTemplate) {
        this.orderMapper = orderMapper;
        this.idempotencyService = idempotencyService;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 支付订单
     *
     * @param userId  用户ID
     * @param orderNo 订单号
     */
    @Transactional(rollbackFor = Exception.class)
    public void payOrder(Long userId, String orderNo) {
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
        if (!order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权操作该订单");
        }

        // 4. 校验订单状态
        if (!"RESERVED".equals(order.getStatus())) {
            throw new IllegalStateException("订单状态不正确，当前状态: " + order.getStatus());
        }

        // 5. 幂等检查
        String idempotencyKey = "idempotency:pay:" + orderNo;
        if (!idempotencyService.tryLock(idempotencyKey, Duration.ofHours(1))) {
            log.warn("[APPOINTMENT] [PAY] 重复支付请求: userId={}, orderNo={}", userId, orderNo);
            return; // 幂等返回，不抛异常
        }

        // 6. 更新订单状态为 PAID（使用乐观锁）
        AppointmentOrder updateOrder = new AppointmentOrder();
        updateOrder.setId(order.getId());
        updateOrder.setStatus("PAID");
        updateOrder.setPaidAt(LocalDateTime.now());
        updateOrder.setVersion(order.getVersion());

        int updated = orderMapper.updateById(updateOrder);
        if (updated == 0) {
            throw new IllegalStateException("订单更新失败，可能已被其他操作修改");
        }

        // 7. 写入 Outbox
        String eventId = "evt-" + snowflakeIdGenerator.nextId();
        String payload = String.format(
            "{\"orderNo\":\"%s\",\"userId\":%d,\"scheduleId\":%d,\"paidAt\":\"%s\"}",
            orderNo, userId, order.getScheduleId(), LocalDateTime.now()
        );

        jdbcTemplate.update(
            """
            INSERT INTO t_outbox (event_id, aggregate_type, aggregate_id, event_type, payload, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, NOW())
            """,
            eventId,
            "AppointmentOrder",
            orderNo,
            "appointment.paid",
            payload,
            "NEW"
        );

        log.info("[APPOINTMENT] [PAY] 支付成功: userId={}, orderNo={}, eventId={}",
            userId, orderNo, eventId);
    }
}
