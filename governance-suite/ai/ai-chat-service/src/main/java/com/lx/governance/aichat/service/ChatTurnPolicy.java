package com.lx.governance.aichat.service;

import com.lx.governance.aichat.config.ProfileCompletionProperties;
import com.lx.governance.aichat.degradation.DegradationLevel;
import com.lx.governance.aichat.degradation.DegradationManager;
import com.lx.governance.aichat.degradation.DegradationStrategyExecutor;
import com.lx.governance.aichat.dto.*;
import com.lx.governance.agentplatform.runtime.PlatformRouteTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ChatTurnPolicy {
    static final String PENDING_REPLY = "本次会话先前已提交复核。你可以问“医生看了吗”查询最新进度。如有紧急不适，请及时就医。";
    static final String FAILURE_REPLY = "本轮分析暂时无法完成，请稍后再试或直接联系医生。";
    enum Route { DIRECT, KNOWLEDGE, AGENT, PENDING, FALLBACK, ERROR }
    record Plan(Route route, TaskTriggerDecision trigger, MedicalKnowledgeQaService.QaResult answer, String reason) {
        boolean needsMemories() {
            return route == Route.DIRECT || route == Route.FALLBACK || route == Route.AGENT;
        }
    }

    private final TaskTriggerRouter router;
    private final MedicalKnowledgeQaService knowledge;
    private final AgentConversationRouteRuntime runtime;
    private final ProfileCompletionProperties completion;
    private final ProfileDiagnosisService diagnosis;
    private final FollowUpStateManager followUp;
    private final FollowUpResponseBuilder questions;
    private final DegradationManager degradation;
    private final DegradationStrategyExecutor strategy;

    TaskTriggerDecision trigger(ChatRequest request, SessionContext context) {
        context.setUserId(request.getUserId() == null || request.getUserId().isBlank()
                ? request.getCustomerId() : request.getUserId());
        if (request.getTenantId() != null && !request.getTenantId().isBlank()) {
            context.setTenantId(request.getTenantId());
        }
        return router.route(TaskTriggerContext.builder()
                .customerId(request.getCustomerId()).userId(context.getUserId())
                .groupId(request.getGroupId()).tenantId(context.getTenantId())
                .conversationId(request.getConversationId()).message(request.getMessage())
                .sessionContext(context).build());
    }

    ChatResponse earlyResponse(ChatRequest request) {
        if (degradation.getCurrentLevel() == DegradationLevel.CIRCUIT_BREAK) {
            return strategy.getCircuitBreakResponse(request.getConversationId());
        }
        ChatResponse cached = strategy.getCachedResponse(request.getCustomerId(), request.getMessage());
        return cached != null && request.getConversationId().equals(cached.getConversationId()) ? cached : null;
    }

    boolean loadProfile(boolean visitor, SessionContext context) {
        String intent = context.getUserIntent() == null ? "" : context.getUserIntent().trim();
        return !visitor && !SessionContext.UNKNOWN_INTENT.equalsIgnoreCase(intent)
                && !"casual_chat".equalsIgnoreCase(intent) && !"fact_query".equalsIgnoreCase(intent);
    }

    int memoryCount(boolean visitor) {
        return !visitor && degradation.isFeatureEnabled("memory") ? strategy.getMemoryRetrievalCount() : 0;
    }

    String profileQuestion(ChatRequest request, SessionContext context, UserProfileDTO profile, TaskTriggerDecision trigger) {
        if (profile == null || SessionContext.UNKNOWN_INTENT.equalsIgnoreCase(context.getUserIntent())
                || !completion.isEnabled() || trigger.shouldUpgradeToAgent() || knowledge.shouldUseKnowledgeQa(trigger)
                || TaskTriggerRouter.hasHighRiskMedicalSignal(request.getMessage())) {
            return null;
        }
        DiagnosisResult result = diagnosis.diagnoseProfile(profile, context, request.getMessage());
        String conversation = context.getConversationId();
        if (!result.isNeedsCompletion()) {
            followUp.markCompleted(conversation);
            return null;
        }
        if (followUp.shouldStopFollowUp(conversation)) {
            return null;
        }
        var prompts = diagnosis.generateFollowUpQuestions(result, completion.getFollowUp().getMaxQuestionsPerRound());
        followUp.recordFollowUp(conversation, prompts, result.getMissingFields().stream()
                .map(DiagnosisResult.MissingField::getFieldPath).toList());
        context.setPendingQuestions(prompts);
        return questions.buildFollowUpMessage(prompts, result);
    }

    Plan decide(ChatRequest request, SessionContext context, TaskTriggerDecision trigger, boolean visitor) {
        if (knowledge.shouldUseKnowledgeQa(trigger)) {
            var answer = knowledge.answer(request.getMessage(), trigger);
            if (!answer.escalateToAgent()) {
                return new Plan(Route.KNOWLEDGE, trigger, answer, null);
            }
            trigger = TaskTriggerDecision.builder().route(TaskTriggerRoute.DOCTOR_ASSISTANT_AGENT)
                    .taskType(TaskType.RISK_STRATIFICATION_AND_ESCALATION)
                    .reasons(List.of(TaskTriggerReason.MEDICAL_SENSITIVE_FACT_QUERY))
                    .summary(answer.escalationReason()).build();
        }
        if (visitor || trigger == null || !trigger.shouldUpgradeToAgent()) {
            return new Plan(Route.DIRECT, trigger, null, null);
        }
        var decision = runtime.evaluateInvocation(request, context, trigger);
        if (decision.target() == PlatformRouteTarget.PENDING_REVIEW_SHORT_CIRCUIT) {
            return new Plan(Route.PENDING, trigger, null, decision.reason());
        }
        if (decision.invokeAgent()) {
            return new Plan(Route.AGENT, trigger, null, decision.reason());
        }
        return new Plan(runtime.fallbackDecision(decision.reason()).target() == PlatformRouteTarget.TERMINAL_ERROR
                ? Route.ERROR : Route.FALLBACK, trigger, null, decision.reason());
    }
}
