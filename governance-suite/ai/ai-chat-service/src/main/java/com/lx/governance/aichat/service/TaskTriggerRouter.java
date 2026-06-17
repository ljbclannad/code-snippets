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

    private static final String INTENT_CASUAL_CHAT = "casual_chat";
    private static final String INTENT_FACT_QUERY = "fact_query";
    private static final String INTENT_GENERAL_HEALTH_CONSULTATION = "general_health_consultation";
    private static final String INTENT_PROFILE_FOLLOW_UP_ANSWER = "profile_follow_up_answer";
    private static final String INTENT_FOLLOW_UP_TREND_ANALYSIS = "follow_up_trend_analysis";
    private static final String INTENT_RISK_REVIEW_REQUEST = "risk_review_request";
    private static final String INTENT_NEXT_ACTION_RECOMMENDATION = "next_action_recommendation";

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
        String intent = normalize(sessionIntent(sessionContext));

        // 画像补全追问阶段优先级最高。
        // 这类消息通常是在回答系统追问，不应该再被升级到医生助手链路。
        if (hasPendingQuestions(sessionContext)) {
            return direct(TaskTriggerReason.PROFILE_COMPLETION_FOLLOW_UP, "当前会话仍处于画像补全追问阶段");
        }

        // 当前置意图分析已经明确识别为“回答追问/补画像”时，也保持在普通对话路径，
        // 避免用户一句简短补充被后续关键词规则误升级。
        if (hasIntent(intent, INTENT_PROFILE_FOLLOW_UP_ANSWER)) {
            return direct(TaskTriggerReason.PROFILE_COMPLETION_FOLLOW_UP, "前置意图识别为画像补全回答");
        }

        // 先把寒暄、感谢、轻量闲聊挡在普通对话路径，
        // 避免把低价值消息误判成需要画像分析或医生助手处理。
        if (hasIntent(intent, INTENT_CASUAL_CHAT) || containsAny(message, CASUAL_CHAT_KEYWORDS)) {
            return direct(TaskTriggerReason.CASUAL_CHAT_SIGNAL, "命中寒暄或轻量闲聊信号");
        }

        // “是什么/解释一下”这类偏事实解释的问题，默认保持 direct chat。
        // 只有在同时带有明显分析诉求时，才继续往后看是否需要升级。
        if (hasIntent(intent, INTENT_FACT_QUERY)
            || (containsAny(message, SIMPLE_FACT_QUERY_KEYWORDS) && !hasIntent(intent, INTENT_FOLLOW_UP_TREND_ANALYSIS)
            && !containsAny(message, ANALYSIS_KEYWORDS))) {
            return direct(TaskTriggerReason.SIMPLE_FACT_QUERY, "当前请求更接近直接解释或事实查询");
        }

        // 这里把路由所需的语义信号一次性抽出来，后续分支只关心“组合条件是否成立”，
        // 让规则更容易读，也方便后续继续扩展更多触发因子。
        boolean trendIntent = hasIntent(intent, INTENT_FOLLOW_UP_TREND_ANALYSIS);
        boolean riskIntent = hasIntent(intent, INTENT_RISK_REVIEW_REQUEST);
        boolean nextActionIntent = hasIntent(intent, INTENT_NEXT_ACTION_RECOMMENDATION);
        boolean analysisRequest = trendIntent || containsAny(message, ANALYSIS_KEYWORDS);
        boolean trendAnalysisRequest = trendIntent || containsAny(message, TREND_ANALYSIS_KEYWORDS);
        boolean strongTrendAnalysisRequest = trendIntent || containsAny(message, STRONG_TREND_ANALYSIS_KEYWORDS);
        boolean humanReviewSignal = riskIntent || containsAny(message, HUMAN_REVIEW_KEYWORDS);
        boolean nextActionRequest = nextActionIntent || containsAny(message, NEXT_ACTION_KEYWORDS);
        boolean complexContextSignal = "complex".equalsIgnoreCase(sessionComplexity(sessionContext))
            || hasRichConversationHistory(sessionContext);

        // 风险/转人工信号优先级最高，一旦命中就直接走风险分层与升级链路。
        if (humanReviewSignal) {
            return agent(
                TaskType.RISK_STRATIFICATION_AND_ESCALATION,
                reasons(TaskTriggerReason.HUMAN_REVIEW_SIGNAL, complexReason(complexContextSignal)),
                "命中风险分层或转人工信号"
            );
        }

        // “下一步怎么办”这类问题，只有在分析诉求、趋势语义或复杂上下文存在时才升级，
        // 目的是避免把过于泛化的建议类提问全部送进 Agent。
        if (nextActionRequest && (nextActionIntent || analysisRequest || complexContextSignal || trendAnalysisRequest)) {
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

        // 随访趋势分析是最后一层升级条件：
        // 要么用户明确要求分析且带有趋势语义，要么虽然表达较短，但强趋势词 + 复杂上下文同时成立。
        if (trendIntent || (analysisRequest && trendAnalysisRequest) || (strongTrendAnalysisRequest && complexContextSignal)) {
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

    private String sessionIntent(SessionContext sessionContext) {
        return sessionContext == null ? null : sessionContext.getUserIntent();
    }

    private boolean hasIntent(String normalizedIntent, String expectedIntent) {
        return expectedIntent.equals(normalizedIntent);
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
