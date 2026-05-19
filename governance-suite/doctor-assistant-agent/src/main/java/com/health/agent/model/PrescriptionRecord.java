package com.health.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 处方记录实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("prescription_record")
public class PrescriptionRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String customerId;
    private String doctorId;
    private String medication;
    private String dosage;
    private String duration;
    private String notes;
    private LocalDateTime createdAt;
}
