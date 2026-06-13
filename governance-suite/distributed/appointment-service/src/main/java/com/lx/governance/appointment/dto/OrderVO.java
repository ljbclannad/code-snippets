package com.lx.governance.appointment.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单视图对象
 */
@Data
public class OrderVO {

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
     * 预约状态
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
}
