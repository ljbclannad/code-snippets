package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.TaskTriggerContext;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.TaskTriggerReason;
import com.lx.governance.aichat.dto.TaskTriggerRoute;
import com.lx.governance.aichat.dto.TaskType;
import com.lx.governance.agentplatform.model.AgentSelectionMode;
import com.lx.governance.agentplatform.model.PlatformConversationRouteType;
import com.lx.governance.agentplatform.model.PlatformRoutingContext;
import com.lx.governance.agentplatform.model.PlatformTaskRoutingDecision;

class TaskTriggerRouterTest {

    private final TaskTriggerRouter router = new TaskTriggerRouter();

    @Test
    void shouldKeepCasualChatOnDirectPath() {
        TaskTriggerDecision decision = router.route(TaskTriggerContext.builder()
            .message("你好，谢谢你")
            .sessionContext(baseContext("simple", 1))
            .build());

        assertFalse(decision.shouldUpgradeToAgent());
        assertEquals(TaskTriggerRoute.DIRECT_CHAT, decision.getRoute());
        assertEquals(TaskType.NONE, decision.getTaskType());
        assertTrue(decision.getReasons().contains(TaskTriggerReason.CASUAL_CHAT_SIGNAL));
    }

    @Test
    void shouldRouteTrendAnalysisToFollowUpAgent() {
        TaskTriggerDecision decision = router.route(TaskTriggerContext.builder()
            .message("帮我分析一下最近两周的减重效果和变化趋势")
            .sessionContext(baseContext("complex", 5, "follow_up_trend_analysis"))
            .build());

        assertTrue(decision.shouldUpgradeToAgent());
        assertEquals(TaskType.PATIENT_FOLLOW_UP_ANALYSIS, decision.getTaskType());
        assertTrue(decision.getReasons().contains(TaskTriggerReason.TREND_ANALYSIS_REQUEST));
        assertTrue(decision.getReasons().contains(TaskTriggerReason.EXPLICIT_ANALYSIS_REQUEST));
    }

    @Test
    void shouldRouteHumanReviewSignalToRiskStratification() {
        TaskTriggerDecision decision = router.route(TaskTriggerContext.builder()
            .message("患者最近状态恶化，是否需要转人工医生复核风险？")
            .sessionContext(baseContext("complex", 6, "risk_review_request"))
            .build());

        assertTrue(decision.shouldUpgradeToAgent());
        assertEquals(TaskType.RISK_STRATIFICATION_AND_ESCALATION, decision.getTaskType());
        assertTrue(decision.getReasons().contains(TaskTriggerReason.HUMAN_REVIEW_SIGNAL));
    }

    @Test
    void shouldRouteNextActionRequestToActionRecommendation() {
        TaskTriggerDecision decision = router.route(TaskTriggerContext.builder()
            .message("结合最近随访情况，下一步应该怎么做，给我建议")
            .sessionContext(baseContext("complex", 4, "next_action_recommendation"))
            .build());

        assertTrue(decision.shouldUpgradeToAgent());
        assertEquals(TaskType.NEXT_FOLLOW_UP_ACTION_RECOMMENDATION, decision.getTaskType());
        assertTrue(decision.getReasons().contains(TaskTriggerReason.NEXT_ACTION_REQUEST));
    }

    @Test
    void shouldKeepProfileFollowUpOnDirectPath() {
        SessionContext context = baseContext("complex", 3);
        context.setPendingQuestions(List.of("最近一周体重变化如何？"));

        TaskTriggerDecision decision = router.route(TaskTriggerContext.builder()
            .message("我最近吃得还可以")
            .sessionContext(context)
            .build());

        assertFalse(decision.shouldUpgradeToAgent());
        assertEquals(TaskTriggerReason.PROFILE_COMPLETION_FOLLOW_UP, decision.getReasons().get(0));
    }

    @Test
    void shouldKeepAmbiguousMessageOnDirectPathByDefault() {
        TaskTriggerDecision decision = router.route(TaskTriggerContext.builder()
            .message("最近还行")
            .sessionContext(baseContext("complex", 2))
            .build());

        assertFalse(decision.shouldUpgradeToAgent());
        assertEquals(TaskTriggerRoute.DIRECT_CHAT, decision.getRoute());
        assertEquals(TaskType.NONE, decision.getTaskType());
        assertTrue(decision.getReasons().contains(TaskTriggerReason.DEFAULT_DIRECT_CHAT));
    }

    @Test
    void shouldRouteTrendAnalysisByIntentEvenWhenMessageIsShort() {
        TaskTriggerDecision decision = router.route(TaskTriggerContext.builder()
            .message("帮我看下")
            .sessionContext(baseContext("complex", 3, "follow_up_trend_analysis"))
            .build());

        assertTrue(decision.shouldUpgradeToAgent());
        assertEquals(TaskType.PATIENT_FOLLOW_UP_ANALYSIS, decision.getTaskType());
        assertTrue(decision.getReasons().contains(TaskTriggerReason.TREND_ANALYSIS_REQUEST));
    }

    @Test
    void shouldKeepProfileFollowUpAnswerOnDirectPathEvenWithoutPendingQuestions() {
        TaskTriggerDecision decision = router.route(TaskTriggerContext.builder()
            .message("最近睡得一般")
            .sessionContext(baseContext("simple", 2, "profile_follow_up_answer"))
            .build());

        assertFalse(decision.shouldUpgradeToAgent());
        assertEquals(TaskTriggerRoute.DIRECT_CHAT, decision.getRoute());
        assertTrue(decision.getReasons().contains(TaskTriggerReason.PROFILE_COMPLETION_FOLLOW_UP));
    }

    @Test
    void shouldRouteMedicalFactQueryToKnowledgeQaPath() {
        TaskTriggerDecision decision = router.route(TaskTriggerContext.builder()
                .message("高血压是什么，平时应该怎么理解")
                .sessionContext(baseContext("simple", 1, "medical_knowledge_qa"))
                .build());

        assertFalse(decision.shouldUpgradeToAgent());
        assertEquals(TaskTriggerRoute.DIRECT_CHAT, decision.getRoute());
        assertTrue(decision.getReasons().contains(TaskTriggerReason.MEDICAL_KNOWLEDGE_QA));
    }

    @Test
    void shouldEscalateSensitiveMedicationFactQueryToAgent() {
        TaskTriggerDecision decision = router.route(TaskTriggerContext.builder()
                .message("二甲双胍能不能直接停药")
                .sessionContext(baseContext("simple", 1, "medical_knowledge_qa"))
                .build());

        assertTrue(decision.shouldUpgradeToAgent());
        assertEquals(TaskType.RISK_STRATIFICATION_AND_ESCALATION, decision.getTaskType());
        assertTrue(decision.getReasons().contains(TaskTriggerReason.MEDICAL_SENSITIVE_FACT_QUERY));
    }

    @Test
    void shouldExposePlatformRoutingLayersForRiskReviewRoute() {
        PlatformTaskRoutingDecision decision = router.route(PlatformRoutingContext.builder()
                .customerId("C001")
                .groupId("group-1")
                .message("患者最近状态恶化，是否需要转人工医生复核风险？")
                .sessionSignals(java.util.Map.of("sessionContext", baseContext("complex", 6, "risk_review_request")))
                .build());

        assertEquals(PlatformConversationRouteType.TASK, decision.getRoute());
        assertEquals("RISK_STRATIFICATION_AND_ESCALATION", decision.getTaskType());
        assertEquals("doctor-assistant-agent", decision.getTargetAgentId());
        assertEquals("doctor-risk-review-workflow", decision.getWorkflowCode());
        assertEquals(AgentSelectionMode.PARALLEL, decision.getSelectionMode());
        assertEquals("TASK", decision.getLayerDecisions().get("direct_chat_vs_task"));
    }

    private SessionContext baseContext(String complexity, int messageCount) {
        return baseContext(complexity, messageCount, null);
    }

    private SessionContext baseContext(String complexity, int messageCount, String userIntent) {
        return SessionContext.builder()
            .complexity(complexity)
            .messageCount(messageCount)
            .userIntent(userIntent)
            .build();
    }
}
