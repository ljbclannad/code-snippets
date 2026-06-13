package com.lx.governance.aichat.rule;

import com.lx.governance.aichat.config.ProfileCompletionProperties;
import com.lx.governance.aichat.dto.DiagnosisResult;
import com.lx.governance.aichat.dto.RequiredField;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.util.ReflectionUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 画像补全规则引擎
 */
@Slf4j
@Component
public class ProfileCompletionRule {

    @Autowired
    private ProfileCompletionProperties properties;

    @Autowired
    private ResourceLoader resourceLoader;

    private RuleConfig ruleConfig;

    /**
     * 初始化加载规则配置
     */
    @PostConstruct
    public void init() {
        try {
            String configPath = properties.getRulesConfig();
            Resource resource = resourceLoader.getResource(configPath);

            if (!resource.exists()) {
                log.warn("规则配置文件不存在: {}, 使用默认规则", configPath);
                ruleConfig = createDefaultRuleConfig();
                return;
            }

            Yaml yaml = new Yaml();
            try (InputStream inputStream = resource.getInputStream()) {
                ruleConfig = yaml.loadAs(inputStream, RuleConfig.class);
                log.info("成功加载画像补全规则配置: {} 条规则", ruleConfig.getRules().size());
            }

        } catch (Exception e) {
            log.error("加载规则配置失败，使用默认规则", e);
            ruleConfig = createDefaultRuleConfig();
        }
    }

    /**
     * 根据意图匹配所需字段
     *
     * @param intent  用户意图
     * @param message 用户消息
     * @return 所需字段列表
     */
    public List<RequiredField> matchRequiredFields(String intent, String message) {
        if (ruleConfig == null || ruleConfig.getRules() == null) {
            log.warn("规则配置未初始化");
            return Collections.emptyList();
        }

        // 1. 精确匹配意图
        List<RequiredField> fields = ruleConfig.getRules().stream()
                .filter(rule -> rule.getIntent() != null && rule.getIntent().equals(intent))
                .flatMap(rule -> rule.getRequiredFields().stream())
                .collect(Collectors.toList());

        if (!fields.isEmpty()) {
            log.debug("精确匹配意图: {}, 找到 {} 个必需字段", intent, fields.size());
            return fields;
        }

        // 2. 模糊匹配（基于关键词）
        if (message != null && !message.isEmpty()) {
            String messageLower = message.toLowerCase();
            fields = ruleConfig.getRules().stream()
                    .filter(rule -> containsKeywords(messageLower, rule.getKeywords()))
                    .flatMap(rule -> rule.getRequiredFields().stream())
                    .collect(Collectors.toList());

            if (!fields.isEmpty()) {
                log.debug("关键词匹配: 找到 {} 个必需字段", fields.size());
                return fields;
            }
        }

        log.debug("未匹配到任何规则: intent={}, message={}", intent, message);
        return Collections.emptyList();
    }

    /**
     * 检查字段是否缺失
     *
     * @param profile   用户画像
     * @param fieldPath 字段路径
     * @return true 如果缺失
     */
    public boolean isFieldMissing(UserProfileDTO profile, String fieldPath) {
        if (profile == null || fieldPath == null) {
            return true;
        }

        try {
            Object value = ReflectionUtils.getFieldValue(profile, fieldPath);
            return ReflectionUtils.isEmpty(value);
        } catch (Exception e) {
            log.warn("检查字段失败: {}", fieldPath, e);
            return true;
        }
    }

    /**
     * 检查消息是否包含关键词
     */
    private boolean containsKeywords(String message, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }

        for (String keyword : keywords) {
            if (message.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 创建默认规则配置
     */
    private RuleConfig createDefaultRuleConfig() {
        RuleConfig config = new RuleConfig();
        config.setRules(new ArrayList<>());

        // 添加一个通用规则
        Rule defaultRule = new Rule();
        defaultRule.setIntent("健康咨询");
        defaultRule.setKeywords(Arrays.asList("健康", "咨询"));

        RequiredField ageField = new RequiredField();
        ageField.setPath("basicInfo.age");
        ageField.setName("年龄");
        ageField.setPriority(DiagnosisResult.FieldPriority.MEDIUM);
        ageField.setQuestion("请问您的年龄是多少？");

        defaultRule.setRequiredFields(Collections.singletonList(ageField));
        config.getRules().add(defaultRule);

        return config;
    }

    /**
     * 规则配置根对象
     */
    @Data
    public static class RuleConfig {
        private List<Rule> rules;
    }

    /**
     * 单条规则
     */
    @Data
    public static class Rule {
        private String intent;
        private List<String> keywords;
        private List<RequiredField> requiredFields;
    }
}
