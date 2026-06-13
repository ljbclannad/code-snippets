package com.health.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 体重记录实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("weight_record")
public class WeightRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String customerId;
    private Double weight;
    private Double bmi;
    private LocalDate recordDate;
    private LocalDateTime createdAt;
}
