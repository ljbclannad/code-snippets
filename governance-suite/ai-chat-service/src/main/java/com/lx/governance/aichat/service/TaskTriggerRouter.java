package com.lx.governance.aichat.service;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.TaskTriggerContext;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.TaskTriggerReason;
import com.lx.governance.aichat.dto.TaskTriggerRoute;
import com.lx.governance.aichat.dto.TaskType;

/**
 * 在普通对话主链路中提供首版任务升级判定。
 * 当前阶段只做判定和记录，不直接改写既有聊天响应流程。
 */
@Service
public class TaskTriggerRouter {

    private static final List<String> CASUAL_CHAT_KEYWORDS = List.of(
        "你好", "您好", "在吗", "谢谢", "感谢", "好的", "嗯", "哦", "哈喽"
    );

    private static final List<String> SIMPLE_FACT_QUERY_KEYWORDS = List.of(
        "是什么", "什么意思", "怎么理解", "解释一下", "介绍一下"
    );

    private static final List<String> ANALYSIS_KEYWORDS = List.of(
        "分析", "判断", "评估", "复盘", "看一下", "帮我看看"
    );

    private static final List<String> TREND_ANALYSIS_KEYWORDS = List.of(
        "最近", "近一周", "近两周", "近一个月", "变化", "趋势", "效果", "进展", "随访"
    );

    private static final List<String> STRONG_TREND_ANALYSIS_KEYWORDS = List.of(
        "近一周", "近两周", "近一个月", "变化", "趋势", "效果", "进展", "没怎么掉", "不明显", "平台期"
    );

    private static final List<String> HUMAN_REVIEW_KEYWORDS = List.of(
        "风险", "严重", "恶化", "转人工", "转医生", "复核", "人工评估", "医生处理"
    );

    private static final List<String> NEXT_ACTION_KEYWORDS = List.of(
        "下一步", "接下来", "怎么做", "怎么办", "建议", "该怎么处理"
    );

    public TaskTriggerDecision route(TaskTriggerContext context) {
        String message = normalize(context.getMessage());
        SessionContext sessionContext = context.getSessionContext();

        if (hasPendingQuestions(sessionContext)) {
            return direct(TaskTriggerReason.PROFILE_COMPLETION_FOLLOW_UP, "当前会话仍处于画像补全追问阶段");
        }

        if (containsAny(message, CASUAL_CHAT_KEYWORDS)) {
            return direct(TaskTriggerReason.CASUAL_CHAT_SIGNAL, "命中寒暄或轻量闲聊信号");
        }

        if (containsAny(message, SIMPLE_FACT_QUERY_KEYWORDS) && !containsAny(message, ANALYSIS_KEYWORDS)) {
            return direct(TaskTriggerReason.SIMPLE_FACT_QUERY, "当前请求更接近直接解释或事实查询");
        }

        boolean analysisRequest = containsAny(message, ANALYSIS_KEYWORDS);
        boolean trendAnalysisRequest = containsAny(message, TREND_ANALYSIS_KEYWORDS);
        boolean strongTrendAnalysisRequest = containsAny(message, STRONG_TREND_ANALYSIS_KEYWORDS);
        boolean humanReviewSignal = containsAny(message, HUMAN_REVIEW_KEYWORDS);
        boolean nextActionRequest = containsAny(message, NEXT_ACTION_KEYWORDS);
        boolean complexContextSignal = "complex".equalsIgnoreCase(sessionComplexity(sessionContext))
            || hasRichConversationHistory(sessionContext);

        if (humanReviewSignal) {
            return agent(
                TaskType.RISK_STRATIFICATION_AND_ESCALATION,
                reasons(TaskTriggerReason.HUMAN_REVIEW_SIGNAL, complexReason(complexContextSignal)),
                "命中风险分层或转人工信号"
            );
        }

        if (nextActionRequest && (analysisRequest || complexContextSignal || trendAnalysisRequest)) {
            return agent(
                TaskType.NEXT_FOLLOW_UP_ACTION_RECOMMENDATION,
                reasons(
                    TaskTriggerReason.NEXT_ACTION_REQUEST,
                    analysisRequest ? TaskTriggerReason.EXPLICIT_ANALYSIS_REQUEST : null,
                    complexReason(complexContextSignal)
                ),
                "命中下一步随访动作建议信号"
            );
        }

        if ((analysisRequest && trendAnalysisRequest) || (strongTrendAnalysisRequest && complexContextSignal)) {
            return agent(
                TaskType.PATIENT_FOLLOW_UP_ANALYSIS,
                reasons(
                    analysisRequest ? TaskTriggerReason.EXPLICIT_ANALYSIS_REQUEST : null,
                    TaskTriggerReason.TREND_ANALYSIS_REQUEST,
                    complexReason(complexContextSignal)
                ),
                "命中随访趋势分析信号"
            );
        }

        return direct(TaskTriggerReason.DEFAULT_DIRECT_CHAT, "未命中 Agent 升级条件，保持普通对话");
    }

    private TaskTriggerDecision direct(TaskTriggerReason reason, String summary) {
        return TaskTriggerDecision.builder()
            .route(TaskTriggerRoute.DIRECT_CHAT)
            .taskType(TaskType.NONE)
            .reasons(List.of(reason))
            .summary(summary)
            .build();
    }

    private TaskTriggerDecision agent(TaskType taskType, List<TaskTriggerReason> reasons, String summary) {
        List<TaskTriggerReason> filteredReasons = reasons.stream()
            .filter(reason -> reason != null)
            .distinct()
            .toList();
        return TaskTriggerDecision.builder()
            .route(TaskTriggerRoute.DOCTOR_ASSISTANT_AGENT)
            .taskType(taskType)
            .reasons(filteredReasons)
            .summary(summary)
            .build();
    }

    private TaskTriggerReason complexReason(boolean complexContextSignal) {
        return complexContextSignal ? TaskTriggerReason.COMPLEX_CONTEXT_SIGNAL : null;
    }

    private List<TaskTriggerReason> reasons(TaskTriggerReason... reasons) {
        return Stream.of(reasons)
            .filter(reason -> reason != null)
            .distinct()
            .toList();
    }

    private boolean hasPendingQuestions(SessionContext sessionContext) {
        return sessionContext != null
            && sessionContext.getPendingQuestions() != null
            && !sessionContext.getPendingQuestions().isEmpty();
    }

    private boolean hasRichConversationHistory(SessionContext sessionContext) {
        return sessionContext != null
            && sessionContext.getMessageCount() != null
            && sessionContext.getMessageCount() >= 4;
    }

    private String sessionComplexity(SessionContext sessionContext) {
        return sessionContext == null ? null : sessionContext.getComplexity();
    }

    private boolean containsAny(String normalizedText, List<String> keywords) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return false;
        }
        return keywords.stream()
            .map(this::normalize)
            .anyMatch(normalizedText::contains);
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    }
}
