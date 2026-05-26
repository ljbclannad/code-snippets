package com.lx.governance.appointment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 预约订单实体
 */
@Data
@TableName("t_appointment_order")
public class AppointmentOrder {

    /**
     * 主键ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 订单号
     */
    private String orderNo;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 排班ID
     */
    private Long scheduleId;

    /**
     * 医生ID
     */
    private Long doctorId;

    /**
     * 科室ID
     */
    private Long deptId;

    /**
     * 就诊日期
     */
    private LocalDate appointmentDate;

    /**
     * 就诊时段
     */
    private String timeSlot;

    /**
     * 挂号费(分)
     */
    private Integer feeCents;

    /**
     * 预约状态 (RESERVED-已预约, PAID-已支付, CANCELLED-已取消, EXPIRED-已过期)
     */
    private String status;

    /**
     * 预约时间
     */
    private LocalDateTime reservedAt;

    /**
     * 支付时间
     */
    private LocalDateTime paidAt;

    /**
     * 取消时间
     */
    private LocalDateTime cancelledAt;

    /**
     * 取消原因
     */
    private String cancelReason;

    /**
     * 乐观锁版本号
     */
    @Version
    private Integer version;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
