package com.lx.governance.aichat.service;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.lx.governance.aichat.dto.PatientVisibleEvidence;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.TaskTriggerReason;
import com.lx.governance.aichat.dto.kb.KbSearchRequest;
import com.lx.governance.aichat.dto.kb.KbSearchResult;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MedicalKnowledgeQaService {

    private static final String DISCLAIMER = "以上内容仅供健康教育参考，不能替代医生面诊或个体化处方建议。";

    private final KnowledgeBaseQaClient knowledgeBaseQaClient;

    public boolean shouldUseKnowledgeQa(TaskTriggerDecision triggerDecision) {
        return triggerDecision != null
                && triggerDecision.getReasons() != null
                && triggerDecision.getReasons().contains(TaskTriggerReason.MEDICAL_KNOWLEDGE_QA);
    }

    public QaResult answer(String query, TaskTriggerDecision triggerDecision) {
        if (containsAny(query, "胸痛", "呼吸困难", "昏迷", "抽搐", "轻生", "自杀")) {
            return QaResult.escalate("高风险医疗问题需要升级 Agent");
        }
        if (containsAny(query, "停药", "换药", "加量", "减量", "处方", "确诊", "诊断")) {
            return QaResult.escalate("医疗安全敏感问题需要升级 Agent");
        }

        KbSearchRequest request = new KbSearchRequest();
        request.setQuery(query);
        request.setTopK(4);
        request.setHybridSearch(true);
        request.setRerank(true);
        request.setUseRewrite(true);
        request.setRetrievalProfile("patient_education");
        request.setApplicableScope("patient_education");
        request.setTrustLevel("MEDIUM");
        request.setSourceTypes(List.of("guideline", "consensus", "hospital", "faq", "drug_label"));

        List<KbSearchResult> rawResults = knowledgeBaseQaClient.search(request);
        List<KbSearchResult> safeResults = rawResults.stream()
                .filter(this::allowPatientAnswer)
                .limit(3)
                .toList();
        if (safeResults.isEmpty()) {
            return QaResult.reply(
                    "目前没有检索到足够可靠的医学依据来直接回答这个问题，建议补充更多背景信息，或联系医生进一步判断。\n\n" + DISCLAIMER,
                    List.of(),
                    true);
        }

        String answer = composeAnswer(query, safeResults);
        List<PatientVisibleEvidence> evidence = safeResults.stream()
                .map(item -> PatientVisibleEvidence.builder()
                        .title(item.getTitle())
                        .summary(truncate(item.getContent(), 72))
                        .source(item.getSource())
                        .trustLevel(item.getTrustLevel())
                        .build())
                .toList();
        return QaResult.reply(answer, evidence, false);
    }

    private boolean allowPatientAnswer(KbSearchResult result) {
        String trustLevel = normalize(result != null ? result.getTrustLevel() : null);
        return "high".equals(trustLevel) || "medium".equals(trustLevel);
    }

    private String composeAnswer(String query, List<KbSearchResult> results) {
        KbSearchResult top = results.get(0);
        StringBuilder builder = new StringBuilder();
        builder.append("根据当前检索到的可靠医学资料，");
        if (top.getContent() != null && !top.getContent().isBlank()) {
            builder.append(truncate(top.getContent(), 120));
        } else {
            builder.append("这类问题通常需要结合具体病情、监测结果和既往治疗方案来判断。");
        }
        if (containsAny(query, "血压", "血糖", "指标", "化验")) {
            builder.append(" 如指标持续异常或伴随不适，建议尽快联系医生复核。");
        } else if (containsAny(query, "症状", "头晕", "乏力", "胸闷")) {
            builder.append(" 如果症状持续加重、反复出现或影响日常活动，建议线下就医。");
        } else {
            builder.append(" 如果实际情况与资料描述不一致，请优先按医生建议处理。");
        }
        builder.append("\n\n").append(DISCLAIMER);
        return builder.toString();
    }

    private boolean containsAny(String text, String... keywords) {
        String normalized = normalize(text);
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    public record QaResult(
            String answer,
            List<PatientVisibleEvidence> patientVisibleEvidence,
            boolean uncertain,
            boolean escalateToAgent,
            String escalationReason) {
        public static QaResult reply(String answer, List<PatientVisibleEvidence> evidence, boolean uncertain) {
            return new QaResult(answer, evidence, uncertain, false, null);
        }

        public static QaResult escalate(String reason) {
            return new QaResult(null, List.of(), false, true, reason);
        }
    }
}
