package com.lx.governance.appointment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 医生实体
 */
@Data
@TableName("t_doctor")
public class Doctor {

    /**
     * 主键ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 医生编码
     */
    private String doctorCode;

    /**
     * 医生姓名
     */
    private String doctorName;

    /**
     * 科室ID
     */
    private Long deptId;

    /**
     * 职称
     */
    private String title;

    /**
     * 擅长领域
     */
    private String specialty;

    /**
     * 简介
     */
    private String introduction;

    /**
     * 排序号
     */
    private Integer sortOrder;

    /**
     * 逻辑删除标记 (0-未删除, 1-已删除)
     */
    @TableLogic
    private Integer deleted;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
