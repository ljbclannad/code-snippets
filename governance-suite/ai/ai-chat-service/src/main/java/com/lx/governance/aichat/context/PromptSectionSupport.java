package com.lx.governance.aichat.context;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.ConversationMessage;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PromptSectionSupport {

    private final ContextBudgetProperties properties;
    private final ConversationHistoryManager conversationHistoryManager;

    public String buildConversationHistory(String customerId, String conversationId, int budget) {
        try {
            int maxRounds = properties.getConversation().getMaxRounds();
            List<ConversationMessage> history = conversationHistoryManager.getRecentHistory(
                    customerId, conversationId, maxRounds);

            if (history.isEmpty()) {
                log.debug("无对话历史");
                return "";
            }

            String formatted = conversationHistoryManager.formatHistory(history, budget);
            log.debug("对话历史构建完成 - 轮数: {}, tokens: {}", history.size() / 2, estimateTokens(formatted));
            return formatted;
        } catch (Exception e) {
            log.error("构建对话历史失败", e);
            return "";
        }
    }

    public String formatSessionContext(SessionContext context) {
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

    public String buildPrioritizedSessionContext(SessionContext context, int budget) {
        if (context == null || budget <= 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        AtomicInteger usedTokens = new AtomicInteger();

        // 第一层：决定本轮回答方向的核心信号，优先保留。
        appendLineWithBudget(sb, "当前话题：", context.getCurrentTopic(), budget, usedTokens, false);
        appendLineWithBudget(sb, "用户意图：", context.getUserIntent(), budget, usedTokens, false);
        appendLineWithBudget(sb, "对话阶段：", context.getConversationPhase(), budget, usedTokens, false);

        // 第二层：影响下一步回答或追问的状态字段。
        appendStringListWithBudget(sb, "待确认问题", context.getPendingQuestions(), budget, usedTokens, 2);
        appendStringListWithBudget(sb, "提及实体", context.getMentionedEntities(), budget, usedTokens, 6);
        appendMapWithBudget(sb, "已提取数据", context.getExtractedData(), budget, usedTokens, 3);

        // 第三层：最近上下文摘要，预算紧张时优先牺牲。
        appendRecentSummariesWithBudget(sb, context.getRecentSummaries(), budget, usedTokens, 2);

        return sb.toString();
    }

    public String extractCoreProfile(UserProfileDTO profile) {
        if (profile == null) {
            return "";
        }

        StringBuilder core = new StringBuilder();
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

    public String extractExtendedProfile(UserProfileDTO profile, String userQuestion, int budget) {
        if (profile == null) {
            return "";
        }

        StringBuilder extended = new StringBuilder();
        int usedTokens = 0;
        QuestionType type = classifyQuestion(userQuestion);
        Map<String, Object> behaviorPreferences = profile.getBehaviorPreferences();
        Map<String, Object> healthProfile = profile.getHealthProfile();

        switch (type) {
            case DIET:
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
                if (healthProfile != null && healthProfile.containsKey("lastAssessmentDate")) {
                    String assessmentInfo = "最近评估：" + healthProfile.get("lastAssessmentDate") + "\n";
                    int tokens = estimateTokens(assessmentInfo);
                    if (usedTokens + tokens <= budget) {
                        extended.append(assessmentInfo);
                    }
                }
                break;
        }

        return extended.toString();
    }

    public String selectRelevantMemories(List<MemoryItemDTO> memories, int budget) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }

        StringBuilder selected = new StringBuilder();
        int usedTokens = 0;
        int count = 0;

        for (MemoryItemDTO memory : memories) {
            if (memory.getSimilarity() == null
                    || memory.getSimilarity() < properties.getMemory().getSimilarityThreshold()) {
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
                log.debug("记忆预算用完，已选择 {} 条记忆", count);
                break;
            }
        }

        log.debug("选择了 {} 条相关记忆，使用 {} tokens", count, usedTokens);
        return selected.toString();
    }

    public String formatShortTermMemories(List<String> memories, int budget) {
        if (memories == null || memories.isEmpty() || budget <= 0) {
            return "";
        }

        StringBuilder selected = new StringBuilder();
        int usedTokens = 0;
        for (String memory : memories) {
            String memoryText = "- " + memory + "\n";
            int tokens = estimateTokens(memoryText);
            if (usedTokens + tokens > budget) {
                break;
            }
            selected.append(memoryText);
            usedTokens += tokens;
        }
        return selected.toString();
    }

    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int chineseChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FA5) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        return (int) (chineseChars * 1.5 + otherChars * 0.25);
    }

    public String compressText(String text, int targetBudget) {
        int estimatedTokens = estimateTokens(text);
        if (estimatedTokens <= targetBudget) {
            return text;
        }

        double ratio = (double) targetBudget / estimatedTokens;
        int targetLength = (int) (text.length() * ratio * 0.9);
        if (targetLength >= text.length()) {
            return text;
        }
        return text.substring(0, targetLength) + "...";
    }

    private void appendLineWithBudget(
            StringBuilder sb,
            String label,
            String value,
            int budget,
            AtomicInteger usedTokens,
            boolean allowSkipWhenTight) {
        if (value == null || value.isBlank() || usedTokens.get() >= budget) {
            return;
        }

        int remainingBudget = budget - usedTokens.get();
        String candidate = label + value + "\n";
        int candidateTokens = estimateTokens(candidate);

        if (candidateTokens <= remainingBudget) {
            sb.append(candidate);
            usedTokens.addAndGet(candidateTokens);
            return;
        }

        String compressedValue = compressText(value, Math.max(remainingBudget - estimateTokens(label + "\n"), 1));
        String compressedCandidate = label + compressedValue + "\n";
        int compressedTokens = estimateTokens(compressedCandidate);
        if (compressedTokens <= remainingBudget) {
            sb.append(compressedCandidate);
            usedTokens.addAndGet(compressedTokens);
            return;
        }

        if (!allowSkipWhenTight && remainingBudget > estimateTokens(label + "...\n")) {
            String fallback = label + "...\n";
            sb.append(fallback);
            usedTokens.addAndGet(estimateTokens(fallback));
        }
    }

    private void appendStringListWithBudget(
            StringBuilder sb,
            String label,
            List<String> items,
            int budget,
            AtomicInteger usedTokens,
            int maxItems) {
        if (items == null || items.isEmpty() || usedTokens.get() >= budget) {
            return;
        }

        String joined = String.join("、", items.stream()
                .filter(item -> item != null && !item.isBlank())
                .limit(maxItems)
                .toList());
        appendLineWithBudget(sb, label + "：", joined, budget, usedTokens, true);
    }

    private void appendMapWithBudget(
            StringBuilder sb,
            String label,
            Map<String, Object> data,
            int budget,
            AtomicInteger usedTokens,
            int maxEntries) {
        if (data == null || data.isEmpty() || usedTokens.get() >= budget) {
            return;
        }

        String joined = data.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .limit(maxEntries)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "；" + right)
                .orElse("");
        appendLineWithBudget(sb, label + "：", joined, budget, usedTokens, true);
    }

    private void appendRecentSummariesWithBudget(
            StringBuilder sb,
            List<com.lx.governance.aichat.dto.MessageSummary> summaries,
            int budget,
            AtomicInteger usedTokens,
            int maxItems) {
        if (summaries == null || summaries.isEmpty() || usedTokens.get() >= budget) {
            return;
        }

        int remainingBudget = budget - usedTokens.get();
        String header = "最近对话：\n";
        int headerTokens = estimateTokens(header);
        if (headerTokens >= remainingBudget) {
            return;
        }

        StringBuilder summaryBuilder = new StringBuilder(header);
        int localUsed = headerTokens;
        int appendedCount = 0;
        for (var summary : summaries) {
            if (summary == null || summary.getUserMessage() == null || summary.getUserMessage().isBlank()) {
                continue;
            }
            String line = "  - " + summary.getUserMessage() + "\n";
            int lineTokens = estimateTokens(line);
            if (localUsed + lineTokens > remainingBudget) {
                String compressed = "  - " + compressText(summary.getUserMessage(),
                        Math.max(remainingBudget - localUsed - estimateTokens("  - \n"), 1)) + "\n";
                int compressedTokens = estimateTokens(compressed);
                if (localUsed + compressedTokens <= remainingBudget) {
                    summaryBuilder.append(compressed);
                    localUsed += compressedTokens;
                    appendedCount++;
                }
                break;
            }
            summaryBuilder.append(line);
            localUsed += lineTokens;
            appendedCount++;
            if (appendedCount >= maxItems) {
                break;
            }
        }

        if (appendedCount > 0) {
            sb.append(summaryBuilder);
            usedTokens.addAndGet(localUsed);
        }
    }

    private QuestionType classifyQuestion(String question) {
        if (question == null) {
            return QuestionType.GENERAL;
        }

        String q = question.toLowerCase();
        if (q.contains("饮食") || q.contains("吃") || q.contains("食物")
                || q.contains("营养") || q.contains("餐")) {
            return QuestionType.DIET;
        } else if (q.contains("运动") || q.contains("锻炼") || q.contains("健身")
                || q.contains("瑜伽") || q.contains("跑步")) {
            return QuestionType.EXERCISE;
        } else if (q.contains("药") || q.contains("治疗") || q.contains("疾病")) {
            return QuestionType.MEDICATION;
        }

        return QuestionType.GENERAL;
    }

    enum QuestionType {
        DIET,
        EXERCISE,
        MEDICATION,
        GENERAL
    }
}
