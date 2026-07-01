package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.TaskTriggerReason;
import com.lx.governance.aichat.dto.TaskTriggerRoute;
import com.lx.governance.aichat.dto.TaskType;
import com.lx.governance.aichat.dto.kb.KbSearchResult;

class MedicalKnowledgeQaServiceTest {

    @Test
    void shouldComposePatientSafeKnowledgeAnswerWithEvidence() {
        KnowledgeBaseQaClient client = mock(KnowledgeBaseQaClient.class);
        MedicalKnowledgeQaService service = new MedicalKnowledgeQaService(client);

        KbSearchResult result = new KbSearchResult();
        result.setTitle("高血压患教");
        result.setContent("建议规律监测血压、控制盐摄入，并根据医生建议坚持生活方式管理。");
        result.setSource("kb");
        result.setTrustLevel("HIGH");
        when(client.search(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(result));

        MedicalKnowledgeQaService.QaResult qaResult = service.answer(
                "高血压平时要注意什么",
                TaskTriggerDecision.builder()
                        .route(TaskTriggerRoute.DIRECT_CHAT)
                        .taskType(TaskType.NONE)
                        .reasons(List.of(TaskTriggerReason.MEDICAL_KNOWLEDGE_QA))
                        .build());

        assertFalse(qaResult.escalateToAgent());
        assertTrue(qaResult.answer().contains("仅供健康教育参考"));
        assertEquals(1, qaResult.patientVisibleEvidence().size());
    }

    @Test
    void shouldEscalateSensitiveMedicationQuestion() {
        KnowledgeBaseQaClient client = mock(KnowledgeBaseQaClient.class);
        MedicalKnowledgeQaService service = new MedicalKnowledgeQaService(client);

        MedicalKnowledgeQaService.QaResult qaResult = service.answer(
                "二甲双胍能不能直接停药",
                TaskTriggerDecision.builder()
                        .route(TaskTriggerRoute.DIRECT_CHAT)
                        .taskType(TaskType.NONE)
                        .reasons(List.of(TaskTriggerReason.MEDICAL_KNOWLEDGE_QA))
                        .build());

        assertTrue(qaResult.escalateToAgent());
        assertTrue(qaResult.escalationReason().contains("敏感"));
    }
}
