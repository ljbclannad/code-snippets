package com.lx.governance.aichat.context;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.ConversationMessage;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;

import lombok.extern.slf4j.Slf4j;

/**
 * 上下文预算管理器
 * 负责在 token 预算约束下，智能选择和组织上下文信息
 *
 * @author LeJinBo
 * @since 2026-05-14
 */
@Slf4j
@Component
public class ContextBudgetManager {

    @Autowired
    private ContextBudgetProperties properties;

    @Autowired
    private ConversationHistoryManager conversationHistoryManager;

    // Token 使用跟踪
    private int lastCoreProfileTokens = 0;
    private int lastSessionContextTokens = 0;
    private int lastMemoriesTokens = 0;
    private int lastConversationTokens = 0;
    private int lastExtendedProfileTokens = 0;
    private int lastTotalTokens = 0;

    /**
     * 构建受预算约束的系统提示词（增强版，包含会话上下文和对话历史）
     */
    public String buildBudgetedSystemPrompt(
            UserProfileDTO profile,
            SessionContext sessionContext,
            List<MemoryItemDTO> memories,
            String customerId,
            String conversationId,
            String userQuestion) {

        long startTime = System.currentTimeMillis();

        // 重置 token 计数
        resetTokenCounters();

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的健康顾问助手。请根据以下用户信息提供个性化的健康建议。\n\n");

        int contextBudget = properties.getTotalBudget() - properties.getUserMessageReserve();
        int remainingBudget = contextBudget;
        int usedTokens = 0;

        // 1. 核心画像（优先级最高，必须包含）
        String coreProfile = extractCoreProfile(profile);
        int coreTokens = estimateTokens(coreProfile);
        int coreProfileBudget = properties.getCoreProfileBudget();

        if (coreTokens <= coreProfileBudget) {
            prompt.append("## 用户核心信息\n").append(coreProfile).append("\n");
            usedTokens += coreTokens;
            remainingBudget -= coreTokens;
            lastCoreProfileTokens = coreTokens;
            log.debug("核心画像 tokens: {}/{}", coreTokens, coreProfileBudget);
        } else {
            // 如果核心画像超预算，进行压缩
            coreProfile = compressText(coreProfile, coreProfileBudget);
            prompt.append("## 用户核心信息\n").append(coreProfile).append("\n");
            usedTokens += coreProfileBudget;
            remainingBudget -= coreProfileBudget;
            lastCoreProfileTokens = coreProfileBudget;
            log.warn("核心画像超预算，已压缩: {} -> {}", coreTokens, coreProfileBudget);
        }

        // 2. 会话上下文（新增，优先级高）
        if (sessionContext != null) {
            int sessionContextBudget = properties.getConversationHistoryBudget(); // 复用对话历史预算
            String sessionContextText = formatSessionContext(sessionContext);
            int sessionContextTokens = estimateTokens(sessionContextText);

            if (sessionContextTokens <= sessionContextBudget && sessionContextTokens <= remainingBudget) {
                prompt.append("## 会话上下文\n").append(sessionContextText).append("\n");
                usedTokens += sessionContextTokens;
                remainingBudget -= sessionContextTokens;
                lastSessionContextTokens = sessionContextTokens;
                log.debug("会话上下文 tokens: {}/{}", sessionContextTokens, sessionContextBudget);
            } else if (remainingBudget > 0) {
                // 压缩会话上下文
                sessionContextText = compressText(sessionContextText, Math.min(sessionContextBudget, remainingBudget));
                int compressedTokens = estimateTokens(sessionContextText);
                prompt.append("## 会话上下文\n").append(sessionContextText).append("\n");
                usedTokens += compressedTokens;
                remainingBudget -= compressedTokens;
                lastSessionContextTokens = compressedTokens;
                log.debug("会话上下文已压缩 tokens: {}/{}", compressedTokens, sessionContextBudget);
            }
        }

        // 3. 相关记忆（优先级高，根据相似度排序）
        int relevantMemoriesBudget = properties.getRelevantMemoriesBudget();
        String relevantMemories = selectRelevantMemories(
                memories,
                Math.min(relevantMemoriesBudget, remainingBudget));
        if (!relevantMemories.isEmpty()) {
            int memoryTokens = estimateTokens(relevantMemories);
            prompt.append("## 相关历史记忆\n").append(relevantMemories).append("\n");
            usedTokens += memoryTokens;
            remainingBudget -= memoryTokens;
            lastMemoriesTokens = memoryTokens;
            log.debug("相关记忆 tokens: {}/{}", memoryTokens, relevantMemoriesBudget);
        }

        // 4. 对话历史（优先级中，最近的对话上下文）
        String conversationHistory = buildConversationHistory(
                customerId, conversationId, Math.min(properties.getConversationHistoryBudget(), remainingBudget));
        if (!conversationHistory.isEmpty()) {
            int conversationTokens = estimateTokens(conversationHistory);
            prompt.append("## 对话历史\n").append(conversationHistory).append("\n");
            usedTokens += conversationTokens;
            remainingBudget -= conversationTokens;
            lastConversationTokens = conversationTokens;
            log.debug("对话历史 tokens: {}/{}", conversationTokens, properties.getConversationHistoryBudget());
        }

        // 5. 扩展画像（优先级低，根据问题类型动态选择）
        int extendedProfileBudget = properties.getExtendedProfileBudget();
        if (remainingBudget > 0) {
            String extendedProfile = extractExtendedProfile(
                    profile,
                    userQuestion,
                    Math.min(extendedProfileBudget, remainingBudget));
            if (!extendedProfile.isEmpty()) {
                int extendedTokens = estimateTokens(extendedProfile);
                prompt.append("## 补充信息\n").append(extendedProfile).append("\n");
                usedTokens += extendedTokens;
                remainingBudget -= extendedTokens;
                lastExtendedProfileTokens = extendedTokens;
                log.debug("扩展画像 tokens: {}/{}", extendedTokens, extendedProfileBudget);
            }
        }

        prompt.append("\n请基于以上信息，提供专业、个性化的健康建议。");

        lastTotalTokens = usedTokens;
        long duration = System.currentTimeMillis() - startTime;

        log.info("上下文构建完成 - 总 tokens: {}/{}, 剩余预算: {}, 耗时: {}ms",
                usedTokens, contextBudget, remainingBudget, duration);

        return prompt.toString();
    }

    /**
     * 构建对话历史文本
     */
    private String buildConversationHistory(String customerId, String conversationId, int budget) {
        try {
            // 获取最近的对话历史
            int maxRounds = properties.getConversation().getMaxRounds();
            List<ConversationMessage> history = conversationHistoryManager.getRecentHistory(
                    customerId, conversationId, maxRounds);

            if (history.isEmpty()) {
                log.debug("无对话历史");
                return "";
            }

            // 格式化对话历史（受预算约束）
            String formatted = conversationHistoryManager.formatHistory(history, budget);

            log.debug("对话历史构建完成 - 轮数: {}, tokens: {}",
                    history.size() / 2, estimateTokens(formatted));

            return formatted;

        } catch (Exception e) {
            log.error("构建对话历史失败", e);
            return "";
        }
    }

    /**
     * 格式化会话上下文
     */
    private String formatSessionContext(SessionContext context) {
        if (context == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        if (context.getCurrentTopic() != null) {
            sb.append("当前话题：").append(context.getCurrentTopic()).append("\n");
        }

        if (context.getConversationPhase() != null) {
            sb.append("对话阶段：").append(context.getConversationPhase()).append("\n");
        }

        if (context.getUserIntent() != null) {
            sb.append("用户意图：").append(context.getUserIntent()).append("\n");
        }

        if (context.getRecentSummaries() != null && !context.getRecentSummaries().isEmpty()) {
            sb.append("最近对话：\n");
            for (var summary : context.getRecentSummaries()) {
                if (summary.getUserMessage() != null) {
                    sb.append("  - ").append(summary.getUserMessage()).append("\n");
                }
            }
        }

        if (context.getMentionedEntities() != null && !context.getMentionedEntities().isEmpty()) {
            sb.append("提及实体：").append(String.join("、", context.getMentionedEntities())).append("\n");
        }

        return sb.toString();
    }

    /**
     * 提取核心画像（必须字段）
     */
    private String extractCoreProfile(UserProfileDTO profile) {
        if (profile == null) {
            return "";
        }

        StringBuilder core = new StringBuilder();

        // 基本信息
        Map<String, Object> basicInfo = profile.getBasicInfo();
        if (basicInfo != null) {
            if (basicInfo.containsKey("name")) {
                core.append("姓名：").append(basicInfo.get("name")).append("\n");
            }
            if (basicInfo.containsKey("age")) {
                core.append("年龄：").append(basicInfo.get("age")).append("岁\n");
            }
            if (basicInfo.containsKey("gender")) {
                core.append("性别：").append(basicInfo.get("gender")).append("\n");
            }
        }

        // 关键健康指标
        Map<String, Object> healthProfile = profile.getHealthProfile();
        if (healthProfile != null && !healthProfile.isEmpty()) {
            if (healthProfile.containsKey("bloodPressure")) {
                core.append("血压：").append(healthProfile.get("bloodPressure")).append("\n");
            }
            if (healthProfile.containsKey("bmi")) {
                core.append("BMI：").append(healthProfile.get("bmi")).append("\n");
            }
            if (healthProfile.containsKey("bloodSugar")) {
                core.append("血糖：").append(healthProfile.get("bloodSugar")).append("\n");
            }

            // 关键过敏信息
            if (healthProfile.containsKey("allergies")) {
                Object allergies = healthProfile.get("allergies");
                if (allergies instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> allergyList = (List<String>) allergies;
                    if (!allergyList.isEmpty()) {
                        core.append("过敏：").append(String.join("、", allergyList)).append("\n");
                    }
                }
            }

            // 主要目标
            if (healthProfile.containsKey("goals")) {
                Object goals = healthProfile.get("goals");
                if (goals instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> goalList = (List<String>) goals;
                    if (!goalList.isEmpty()) {
                        core.append("健康目标：").append(String.join("、", goalList)).append("\n");
                    }
                }
            }
        }

        return core.toString();
    }

    /**
     * 提取扩展画像（根据问题类型动态选择）
     */
    private String extractExtendedProfile(
            UserProfileDTO profile,
            String userQuestion,
            int budget) {

        if (profile == null) {
            return "";
        }

        StringBuilder extended = new StringBuilder();
        int usedTokens = 0;

        // 根据问题类型选择相关字段
        QuestionType type = classifyQuestion(userQuestion);

        Map<String, Object> behaviorPreferences = profile.getBehaviorPreferences();
        Map<String, Object> healthProfile = profile.getHealthProfile();

        switch (type) {
            case DIET:
                // 饮食相关问题：注入饮食偏好
                if (behaviorPreferences != null && behaviorPreferences.containsKey("dietaryPreferences")) {
                    Object dietPrefs = behaviorPreferences.get("dietaryPreferences");
                    if (dietPrefs instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> dietPrefList = (List<String>) dietPrefs;
                        if (!dietPrefList.isEmpty()) {
                            String dietInfo = "饮食偏好：" + String.join("、", dietPrefList) + "\n";
                            int tokens = estimateTokens(dietInfo);
                            if (usedTokens + tokens <= budget) {
                                extended.append(dietInfo);
                                usedTokens += tokens;
                            }
                        }
                    }
                }
                break;

            case EXERCISE:
                // 运动相关问题：注入运动习惯
                if (behaviorPreferences != null && behaviorPreferences.containsKey("exerciseHabits")) {
                    Object exerciseHabits = behaviorPreferences.get("exerciseHabits");
                    if (exerciseHabits instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> exerciseList = (List<String>) exerciseHabits;
                        if (!exerciseList.isEmpty()) {
                            String exerciseInfo = "运动习惯：" + String.join("、", exerciseList) + "\n";
                            int tokens = estimateTokens(exerciseInfo);
                            if (usedTokens + tokens <= budget) {
                                extended.append(exerciseInfo);
                                usedTokens += tokens;
                            }
                        }
                    }
                }
                break;

            case MEDICATION:
                // 用药相关问题：注入慢性病和用药信息
                if (healthProfile != null && healthProfile.containsKey("chronicDiseases")) {
                    Object chronicDiseases = healthProfile.get("chronicDiseases");
                    if (chronicDiseases instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> diseaseList = (List<String>) chronicDiseases;
                        if (!diseaseList.isEmpty()) {
                            String diseaseInfo = "慢性病：" + String.join("、", diseaseList) + "\n";
                            int tokens = estimateTokens(diseaseInfo);
                            if (usedTokens + tokens <= budget) {
                                extended.append(diseaseInfo);
                                usedTokens += tokens;
                            }
                        }
                    }
                }
                break;

            case GENERAL:
            default:
                // 通用问题：添加最近评估时间
                if (healthProfile != null && healthProfile.containsKey("lastAssessmentDate")) {
                    String assessmentInfo = "最近评估：" + healthProfile.get("lastAssessmentDate") + "\n";
                    int tokens = estimateTokens(assessmentInfo);
                    if (usedTokens + tokens <= budget) {
                        extended.append(assessmentInfo);
                        usedTokens += tokens;
                    }
                }
                break;
        }

        return extended.toString();
    }

    /**
     * 选择相关记忆（基于相似度和预算）
     */
    private String selectRelevantMemories(List<MemoryItemDTO> memories, int budget) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }

        StringBuilder selected = new StringBuilder();
        int usedTokens = 0;
        int count = 0;

        // 记忆已经按相似度排序（从向量检索返回）
        for (MemoryItemDTO memory : memories) {
            // 跳过低相似度的记忆
            if (memory.getSimilarity() < properties.getMemory().getSimilarityThreshold()) {
                continue;
            }

            String memoryText = String.format("- %s (相关度: %.0f%%)\n",
                    memory.getContent(),
                    memory.getSimilarity() * 100);

            int tokens = estimateTokens(memoryText);

            if (usedTokens + tokens <= budget) {
                selected.append(memoryText);
                usedTokens += tokens;
                count++;
            } else {
                // 预算用完，停止添加
                log.debug("记忆预算用完，已选择 {} 条记忆", count);
                break;
            }
        }

        log.debug("选择了 {} 条相关记忆，使用 {} tokens", count, usedTokens);
        return selected.toString();
    }

    /**
     * 估算文本的 token 数量
     * 简单估算：中文 1 字符 ≈ 1.5 tokens，英文 1 单词 ≈ 1.3 tokens
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        // 统计中文字符
        int chineseChars = 0;
        int otherChars = 0;

        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FA5) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }

        // 估算：中文 1.5 tokens/字符，其他 0.25 tokens/字符
        return (int) (chineseChars * 1.5 + otherChars * 0.25);
    }

    /**
     * 压缩文本（当超出预算时）
     */
    private String compressText(String text, int targetBudget) {
        int estimatedTokens = estimateTokens(text);
        if (estimatedTokens <= targetBudget) {
            return text;
        }

        // 按比例截断
        double ratio = (double) targetBudget / estimatedTokens;
        int targetLength = (int) (text.length() * ratio * 0.9); // 留 10% 余量

        if (targetLength >= text.length()) {
            return text;
        }

        return text.substring(0, targetLength) + "...";
    }

    /**
     * 问题分类
     */
    private QuestionType classifyQuestion(String question) {
        if (question == null) {
            return QuestionType.GENERAL;
        }

        String q = question.toLowerCase();

        if (q.contains("饮食") || q.contains("吃") || q.contains("食物") ||
                q.contains("营养") || q.contains("餐")) {
            return QuestionType.DIET;
        } else if (q.contains("运动") || q.contains("锻炼") || q.contains("健身") ||
                q.contains("瑜伽") || q.contains("跑步")) {
            return QuestionType.EXERCISE;
        } else if (q.contains("药") || q.contains("治疗") || q.contains("疾病")) {
            return QuestionType.MEDICATION;
        }

        return QuestionType.GENERAL;
    }

    /**
     * 问题类型枚举
     */
    enum QuestionType {
        DIET, // 饮食
        EXERCISE, // 运动
        MEDICATION, // 用药
        GENERAL // 通用
    }

    /**
     * 重置 token 计数器
     */
    private void resetTokenCounters() {
        lastCoreProfileTokens = 0;
        lastSessionContextTokens = 0;
        lastMemoriesTokens = 0;
        lastConversationTokens = 0;
        lastExtendedProfileTokens = 0;
        lastTotalTokens = 0;
    }

    /**
     * 获取上次构建的各部分 token 使用情况
     */
    public int getLastCoreProfileTokens() {
        return lastCoreProfileTokens;
    }

    public int getLastSessionContextTokens() {
        return lastSessionContextTokens;
    }

    public int getLastMemoriesTokens() {
        return lastMemoriesTokens;
    }

    public int getLastConversationTokens() {
        return lastConversationTokens;
    }

    public int getLastExtendedProfileTokens() {
        return lastExtendedProfileTokens;
    }

    public int getLastTotalTokens() {
        return lastTotalTokens;
    }
}
