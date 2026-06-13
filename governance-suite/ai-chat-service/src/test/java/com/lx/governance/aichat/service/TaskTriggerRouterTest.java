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
            .sessionContext(baseContext("complex", 5))
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
            .sessionContext(baseContext("complex", 6))
            .build());

        assertTrue(decision.shouldUpgradeToAgent());
        assertEquals(TaskType.RISK_STRATIFICATION_AND_ESCALATION, decision.getTaskType());
        assertTrue(decision.getReasons().contains(TaskTriggerReason.HUMAN_REVIEW_SIGNAL));
    }

    @Test
    void shouldRouteNextActionRequestToActionRecommendation() {
        TaskTriggerDecision decision = router.route(TaskTriggerContext.builder()
            .message("结合最近随访情况，下一步应该怎么做，给我建议")
            .sessionContext(baseContext("complex", 4))
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

    private SessionContext baseContext(String complexity, int messageCount) {
        return SessionContext.builder()
            .complexity(complexity)
            .messageCount(messageCount)
            .build();
    }
}
