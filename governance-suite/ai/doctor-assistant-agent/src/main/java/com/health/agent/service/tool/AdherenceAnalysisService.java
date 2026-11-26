package com.health.agent.service.tool;

import com.health.agent.model.ChatMessage;
import com.health.agent.model.FollowUpTask;
import com.health.agent.model.WeightRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 饮食/运动依从性分析服务
 */
@Service
public class AdherenceAnalysisService {

    @Value("${agent.adherence.analysis-window-days:14}")
    private int analysisWindowDays;

    @Value("${agent.adherence.min-weight-records:3}")
    private int minWeightRecords;

    public Map<String, Object> analyze(
        String customerId,
        List<WeightRecord> weightRecords,
        List<ChatMessage> chatMessages,
        List<FollowUpTask> followUpTasks
    ) {
        List<String> evidence = new ArrayList<>();
        List<String> gaps = new ArrayList<>();

        int dietScore = 1;
        int exerciseScore = 1;

        int recentWeightRecords = (int) weightRecords.stream()
            .filter(record -> record.getRecordDate() != null
                && !record.getRecordDate().isBefore(LocalDate.now().minusDays(analysisWindowDays)))
            .count();
        if (recentWeightRecords >= minWeightRecords) {
            dietScore++;
            exerciseScore++;
            evidence.add("近两周体重记录较完整");
        } else {
            gaps.add("近两周体重记录不足");
        }

        boolean hasDietPositive = hasAnyUserSignal(chatMessages, List.of("按饮食计划", "控制饮食", "少吃", "记录饮食", "晚餐控制"));
        boolean hasDietNegative = hasAnyUserSignal(chatMessages, List.of("夜宵", "没控制", "暴食", "奶茶", "甜食"));
        boolean hasExercisePositive = hasAnyUserSignal(chatMessages, List.of("快走", "跑步", "运动", "训练", "坚持住", "有氧"));
        boolean hasExerciseNegative = hasAnyUserSignal(chatMessages, List.of("没运动", "停了", "中断", "偷懒", "没怎么运动"));

        if (hasDietPositive) {
            dietScore++;
            evidence.add("聊天中出现正向饮食执行信号");
        }
        if (hasDietNegative) {
            dietScore--;
            evidence.add("聊天中出现饮食失控或未按计划执行信号");
        }
        if (hasExercisePositive) {
            exerciseScore++;
            evidence.add("聊天中出现正向运动执行信号");
        }
        if (hasExerciseNegative) {
            exerciseScore--;
            evidence.add("聊天中出现运动中断或缺失信号");
        }

        long overduePendingTasks = followUpTasks.stream()
            .filter(task -> task.getStatus() == FollowUpTask.TaskStatusEnum.PENDING)
            .filter(task -> task.getDueDate() != null && task.getDueDate().isBefore(LocalDate.now()))
            .count();
        if (overduePendingTasks > 0) {
            dietScore--;
            exerciseScore--;
            gaps.add("存在逾期未完成的随访任务");
        }

        String dietAdherence = toLevel(dietScore);
        String exerciseAdherence = toLevel(exerciseScore);
        String overallAdherence = toLevel(Math.min(dietScore, exerciseScore));
        String riskLevel = "HIGH".equals(overallAdherence)
            ? "LOW"
            : "MEDIUM".equals(overallAdherence) ? "MEDIUM" : "HIGH";

        List<String> recommendations = new ArrayList<>();
        if ("LOW".equals(dietAdherence)) {
            recommendations.add("优先复核近期饮食执行情况，明确夜宵、加餐和高糖摄入问题");
        }
        if ("LOW".equals(exerciseAdherence)) {
            recommendations.add("复核近期运动执行频率，先恢复低门槛有氧活动");
        }
        if (recommendations.isEmpty()) {
            recommendations.add("继续保持当前减重执行计划，并维持规律记录");
        }

        return Map.of(
            "customerId", customerId,
            "dietAdherence", dietAdherence,
            "exerciseAdherence", exerciseAdherence,
            "overallAdherence", overallAdherence,
            "riskLevel", riskLevel,
            "evidence", evidence,
            "evidenceSources", List.of("WEIGHT_HISTORY", "CHAT_HISTORY", "FOLLOW_UP_TASK"),
            "gaps", gaps,
            "recommendations", recommendations
        );
    }

    private boolean hasAnyUserSignal(List<ChatMessage> messages, List<String> keywords) {
        return messages.stream()
            .filter(message -> "USER".equalsIgnoreCase(message.getSender()))
            .map(ChatMessage::getMessage)
            .filter(text -> text != null && !text.isBlank())
            .map(text -> text.toLowerCase(Locale.ROOT))
            .anyMatch(text -> keywords.stream().anyMatch(keyword -> text.contains(keyword.toLowerCase(Locale.ROOT))));
    }

    private String toLevel(int score) {
        if (score >= 2) {
            return "HIGH";
        }
        if (score == 1) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
