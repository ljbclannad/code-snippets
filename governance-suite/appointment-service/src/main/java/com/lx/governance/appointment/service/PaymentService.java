package com.lx.governance.appointment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.appointment.entity.AppointmentOrder;
import com.lx.governance.appointment.mapper.AppointmentOrderMapper;
import com.lx.governance.common.id.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
public class PaymentService {

    private static final String STATUS_RESERVED = "RESERVED";
    private static final String STATUS_PAID = "PAID";
    private static final String OUTBOX_STATUS_NEW = "NEW";
    private static final String AGGREGATE_TYPE = "AppointmentOrder";
    private static final String EVENT_TYPE_PAID = "appointment.paid";

    private final AppointmentOrderMapper orderMapper;
    private final IdempotencyService idempotencyService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PaymentService(AppointmentOrderMapper orderMapper,
                          IdempotencyService idempotencyService,
                          SnowflakeIdGenerator snowflakeIdGenerator,
                          JdbcTemplate jdbcTemplate,
                          ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.idempotencyService = idempotencyService;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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

        // 3. 校验用户权限（防止空指针）
        if (!Objects.equals(order.getUserId(), userId)) {
            throw new IllegalArgumentException("无权操作该订单");
        }

        // 4. 校验订单状态
        if (!STATUS_RESERVED.equals(order.getStatus())) {
            throw new IllegalStateException("订单状态不正确，当前状态: " + order.getStatus());
        }

        // 5. 幂等检查
        String idempotencyKey = "idempotency:pay:" + orderNo;
        if (!idempotencyService.tryLock(idempotencyKey, Duration.ofHours(1))) {
            log.warn("[APPOINTMENT] [PAY] 重复支付请求: userId={}, orderNo={}", userId, orderNo);
            return; // 幂等返回，不抛异常
        }

        // 6. 更新订单状态为 PAID（使用乐观锁）
        LocalDateTime paidAt = LocalDateTime.now();
        AppointmentOrder updateOrder = new AppointmentOrder();
        updateOrder.setId(order.getId());
        updateOrder.setStatus(STATUS_PAID);
        updateOrder.setPaidAt(paidAt);
        updateOrder.setVersion(order.getVersion());

        int updated = orderMapper.updateById(updateOrder);
        if (updated == 0) {
            // 乐观锁失败，检查订单是否已支付（处理幂等性竞态窗口）
            AppointmentOrder latestOrder = orderMapper.selectById(order.getId());
            if (latestOrder != null && STATUS_PAID.equals(latestOrder.getStatus())) {
                log.info("[APPOINTMENT] [PAY] 订单已支付，幂等返回: orderNo={}", orderNo);
                return;
            }
            throw new IllegalStateException("订单更新失败，可能已被其他操作修改");
        }

        // 7. 写入 Outbox（使用 Jackson 序列化，防止 JSON 注入）
        String eventId = "evt-" + snowflakeIdGenerator.nextId();
        String payload;
        try {
            Map<String, Object> payloadMap = Map.of(
                "orderNo", orderNo,
                "userId", userId,
                "scheduleId", order.getScheduleId(),
                "paidAt", paidAt.toString()
            );
            payload = objectMapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException e) {
            log.error("[APPOINTMENT] [PAY] JSON序列化失败: orderNo={}", orderNo, e);
            throw new IllegalStateException("事件数据序列化失败", e);
        }

        jdbcTemplate.update(
            """
            INSERT INTO t_outbox (event_id, aggregate_type, aggregate_id, event_type, payload, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, NOW())
            """,
            eventId,
            AGGREGATE_TYPE,
            orderNo,
            EVENT_TYPE_PAID,
            payload,
            OUTBOX_STATUS_NEW
        );

        log.info("[APPOINTMENT] [PAY] 支付成功: userId={}, orderNo={}, eventId={}",
            userId, orderNo, eventId);
    }
}
