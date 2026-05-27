package com.lx.governance.appointment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 排班实体
 */
@Data
@TableName("t_schedule")
public class Schedule {

    /**
     * 主键ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 医生ID
     */
    private Long doctorId;

    /**
     * 科室ID
     */
    private Long deptId;

    /**
     * 排班日期
     */
    private LocalDate scheduleDate;

    /**
     * 时段 (morning-上午, afternoon-下午, evening-晚上)
     */
    private String timeSlot;

    /**
     * 总号源数
     */
    private Integer totalQuota;

    /**
     * 剩余号源数
     */
    @TableField("available_quota")
    private Integer remainQuota;

    /**
     * 挂号费用（分）
     */
    private Integer feeCents;

    /**
     * 状态 (AVAILABLE-可预约, FULL-已满, CANCELLED-已取消)
     */
    private String status;

    /**
     * 乐观锁版本号
     */
    @Version
    private Integer version;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private LocalDateTime updateTime;
}
