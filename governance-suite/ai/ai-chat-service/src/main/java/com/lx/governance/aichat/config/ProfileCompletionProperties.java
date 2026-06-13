package com.lx.governance.aichat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 画像补全配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "profile-completion")
public class ProfileCompletionProperties {

    /**
     * 是否启用画像补全功能
     */
    private boolean enabled = true;

    /**
     * 追问控制配置
     */
    private FollowUpConfig followUp = new FollowUpConfig();

    /**
     * 规则配置文件路径
     */
    private String rulesConfig = "classpath:profile-completion-rules.yml";

    /**
     * 追问控制配置
     */
    @Data
    public static class FollowUpConfig {

        /**
         * 最多追问轮数
         */
        private int maxRounds = 3;

        /**
         * 每轮最多追问问题数
         */
        private int maxQuestionsPerRound = 2;

        /**
         * 追问超时时间（分钟）
         */
        private int timeoutMinutes = 10;
    }
}
