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
 * 健康知识实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("health_knowledge")
public class HealthKnowledge {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String topic;

    private String category;

    private String title;

    private String content;

    private String tags;

    private String source;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public enum CategoryEnum {
        NUTRITION,    // 营养
        EXERCISE,     // 运动
        DISEASE,      // 疾病
        MEDICATION,   // 用药
        LIFESTYLE     // 生活方式
    }
}
