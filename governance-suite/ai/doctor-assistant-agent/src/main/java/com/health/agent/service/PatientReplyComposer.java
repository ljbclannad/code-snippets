package com.health.agent.service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.health.agent.dto.patient.PatientVisibleEvidence;
import com.health.agent.dto.patient.StructuredTaskResult;
import com.health.agent.dto.patient.TaskType;
import com.health.agent.model.StructuredEvidenceItem;

@Component
public class PatientReplyComposer {

    public String composeCandidateReply(
        TaskType taskType,
        StructuredTaskResult structuredResult,
        boolean needsHumanReview
    ) {
        return switch (taskType) {
            case PATIENT_FOLLOW_UP_ANALYSIS -> composeFollowUpReply(structuredResult, needsHumanReview);
            case RISK_STRATIFICATION_AND_ESCALATION -> composeRiskReply(structuredResult);
            case NEXT_FOLLOW_UP_ACTION_RECOMMENDATION -> composeNextActionReply(structuredResult);
            default -> defaultReply(structuredResult);
        };
    }

    public String composeAutoAnalysisFailureReply() {
        return "当前自动分析还没形成稳定结论，建议先补充近几天的症状、监测结果和执行情况，再由医生进一步评估。";
    }

    public List<PatientVisibleEvidence> buildPatientVisibleEvidence(StructuredTaskResult structuredResult) {
        if (structuredResult == null || structuredResult.getEvidenceDetails() == null) {
            return List.of();
        }
        return structuredResult.getEvidenceDetails().stream()
                .filter(Objects::nonNull)
                .filter(item -> !Boolean.TRUE.equals(item.getUpstream()))
                .filter(item -> !isLowTrust(item))
                .map(this::toPatientVisibleEvidence)
                .filter(Objects::nonNull)
                .limit(3)
                .collect(Collectors.toList());
    }

    private String composeFollowUpReply(StructuredTaskResult structuredResult, boolean needsHumanReview) {
        String actionHint = firstAction(structuredResult);
        String reply = "我先帮你整理了这次随访里最需要关注的信息，建议继续补充近几天的关键数据和变化情况。";
        if (actionHint != null) {
            reply += " 下一步可以先" + actionHint + "。";
        }
        if (needsHumanReview) {
            reply += " 这部分也会同步给医生进一步查看。";
        }
        return reply;
    }

    private String composeRiskReply(StructuredTaskResult structuredResult) {
        String summary = structuredResult != null ? structuredResult.getSummary() : null;
        if (summary != null && (summary.contains("高风险") || summary.contains("立即"))) {
            return "结合你刚才提供的情况，建议尽快联系医生进一步评估；如果症状明显加重，请及时线下就医。";
        }
        return "结合目前提供的信息，建议先尽快联系医生进一步评估，在明确判断前先按保守方案观察。";
    }

    private String composeNextActionReply(StructuredTaskResult structuredResult) {
        String actionHint = firstAction(structuredResult);
        if (actionHint != null) {
            return "下一步建议先" + actionHint + "，再结合医生意见安排后续随访。";
        }
        return "下一步建议先补充最近几天的监测结果、症状变化和执行情况，再结合医生意见安排后续随访。";
    }

    private String defaultReply(StructuredTaskResult structuredResult) {
        return Objects.requireNonNullElse(
                structuredResult != null ? structuredResult.getSummary() : null,
                "已收到你的情况，我们会继续谨慎评估。");
    }

    private String firstAction(StructuredTaskResult structuredResult) {
        List<String> actions = structuredResult != null ? structuredResult.getRecommendedActions() : null;
        if (actions == null || actions.isEmpty()) {
            return null;
        }
        return actions.get(0);
    }

    private boolean isLowTrust(StructuredEvidenceItem item) {
        return item.getTrustLevel() != null && "LOW".equalsIgnoreCase(item.getTrustLevel());
    }

    private PatientVisibleEvidence toPatientVisibleEvidence(StructuredEvidenceItem item) {
        String summary = firstNonBlank(item.getSnippet(), item.getSummary());
        String title = firstNonBlank(item.getTitle(), item.getSource());
        if (summary == null && title == null) {
            return null;
        }
        return PatientVisibleEvidence.builder()
                .title(title)
                .summary(summary)
                .source(item.getSource())
                .trustLevel(item.getTrustLevel())
                .build();
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }
}
