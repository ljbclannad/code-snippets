package com.lx.governance.appointment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 号源退还日志实体
 */
@Data
@TableName("t_quota_refund_log")
public class QuotaRefundLog {

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
     * 排班ID
     */
    private Long scheduleId;

    /**
     * 退还类型 (CANCEL-取消订单, EXPIRE-订单过期)
     */
    private String refundType;

    /**
     * 退还时间
     */
    private LocalDateTime refundedAt;

    /**
     * 退还状态 (SUCCESS-成功, FAILED-失败)
     */
    @TableField(exist = false)
    private String status;

    /**
     * 失败原因
     */
    @TableField(exist = false)
    private String failReason;

    /**
     * 重试次数
     */
    @TableField(exist = false)
    private Integer retryCount;
}
