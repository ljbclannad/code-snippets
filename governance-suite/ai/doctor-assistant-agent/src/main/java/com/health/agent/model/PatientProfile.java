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
 * 患者档案实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("patient_profile")
public class PatientProfile {

    @TableId(type = IdType.INPUT)
    private String customerId;

    private String name;
    private Integer age;
    private String gender;
    private Double height;
    private Double currentWeight;
    private Double targetWeight;
    private String chronicDiseases;  // JSON 字符串
    private String allergies;  // JSON 字符串
    private Double bmi;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
