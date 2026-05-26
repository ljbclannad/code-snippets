package com.lx.governance.appointment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预约幂等性实体
 */
@Data
@TableName("t_appointment_idempotency")
public class AppointmentIdempotency {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 幂等键 (用户ID + 排班ID)
     */
    private String idempotencyKey;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 排班ID
     */
    private Long scheduleId;

    /**
     * 订单号
     */
    private String orderNo;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
