package com.lx.governance.appointment.service;

import com.lx.governance.appointment.entity.AppointmentIdempotency;
import com.lx.governance.appointment.entity.AppointmentOrder;
import com.lx.governance.appointment.entity.Schedule;
import com.lx.governance.appointment.mapper.AppointmentIdempotencyMapper;
import com.lx.governance.appointment.mapper.AppointmentOrderMapper;
import com.lx.governance.appointment.mapper.ScheduleMapper;
import com.lx.governance.common.id.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class StreamConsumerService {

    private final StringRedisTemplate redisTemplate;
    private final ScheduleMapper scheduleMapper;
    private final AppointmentOrderMapper orderMapper;
    private final AppointmentIdempotencyMapper idempotencyMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final com.lx.governance.appointment.websocket.AppointmentPushService appointmentPushService;

    private static final String STREAM_KEY = "appointment:stream:orders";
    private static final String CONSUMER_GROUP = "appointment-consumer-group";
    private static final String CONSUMER_NAME = "consumer-1";

    public StreamConsumerService(
            StringRedisTemplate redisTemplate,
            ScheduleMapper scheduleMapper,
            AppointmentOrderMapper orderMapper,
            AppointmentIdempotencyMapper idempotencyMapper,
            SnowflakeIdGenerator snowflakeIdGenerator,
            com.lx.governance.appointment.websocket.AppointmentPushService appointmentPushService) {
        this.redisTemplate = redisTemplate;
        this.scheduleMapper = scheduleMapper;
        this.orderMapper = orderMapper;
        this.idempotencyMapper = idempotencyMapper;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.appointmentPushService = appointmentPushService;
    }

    @PostConstruct
    public void initConsumerGroup() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, CONSUMER_GROUP);
            log.info("[APPOINTMENT] [STREAM] 消费组创建成功: {}", CONSUMER_GROUP);
        } catch (Exception e) {
            log.info("[APPOINTMENT] [STREAM] 消费组已存在: {}", CONSUMER_GROUP);
        }
    }

    @Scheduled(fixedDelay = 50)
    @SuppressWarnings("unchecked")
    public void consumeStream() {
        try {
            StreamOperations<String, String, String> streamOps = redisTemplate.opsForStream();

            // 1. 从 Stream 读取消息
            List<MapRecord<String, String, String>> records = streamOps.read(
                    Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                    StreamReadOptions.empty().count(100).block(Duration.ofMillis(100)),
                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                );

            if (records == null || records.isEmpty()) {
                return;
            }

            // 2. 批量处理消息
            for (MapRecord<String, String, String> record : records) {
                try {
                    boolean shouldAck = processMessage(record);

                    if (shouldAck) {
                        // 3. 仅在成功处理或幂等重复时 ACK
                        streamOps.acknowledge(
                            STREAM_KEY,
                            CONSUMER_GROUP,
                            record.getId()
                        );
                    }

                } catch (Exception e) {
                    log.error("[APPOINTMENT] [STREAM] 处理消息失败: recordId={}", record.getId(), e);
                    // 不 ACK，下次重试
                }
            }

        } catch (Exception e) {
            log.error("[APPOINTMENT] [STREAM] 消费 Stream 失败", e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean processMessage(MapRecord<String, String, String> record) {
        Long userId = Long.parseLong(record.getValue().get("userId"));
        Long scheduleId = Long.parseLong(record.getValue().get("scheduleId"));
        String requestId = record.getValue().get("requestId");

        // 1. 查询排班信息
        Schedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            throw new IllegalStateException("排班不存在: scheduleId=" + scheduleId);
        }

        // 2. 生成订单号
        String orderNo = "APT" + snowflakeIdGenerator.nextId();

        // 3. 创建订单记录
        AppointmentOrder order = new AppointmentOrder();
        order.setId(snowflakeIdGenerator.nextId());
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setScheduleId(scheduleId);
        order.setDeptId(schedule.getDeptId());
        order.setDoctorId(schedule.getDoctorId());
        order.setAppointmentDate(schedule.getScheduleDate());
        order.setTimeSlot(schedule.getTimeSlot());
        order.setFeeCents(schedule.getFeeCents());
        order.setStatus("RESERVED");
        order.setReservedAt(LocalDateTime.now());

        // 4. 写入幂等表
        AppointmentIdempotency idempotency = new AppointmentIdempotency();
        idempotency.setIdempotencyKey(requestId);
        idempotency.setUserId(userId);
        idempotency.setScheduleId(scheduleId);
        idempotency.setOrderNo(orderNo);

        try {
            // 5. 事务写入
            orderMapper.insert(order);
            idempotencyMapper.insert(idempotency);

            log.info("[APPOINTMENT] [STREAM] 订单创建成功: orderNo={}, userId={}, scheduleId={}",
                orderNo, userId, scheduleId);
            appointmentPushService.pushOrderCreated(userId, scheduleId, requestId, orderNo);
            return true;

        } catch (DuplicateKeyException e) {
            // 幂等键冲突，说明已处理过
            log.warn("[APPOINTMENT] [STREAM] 重复消息，跳过: requestId={}", requestId);
            return true;
        }
    }
}
