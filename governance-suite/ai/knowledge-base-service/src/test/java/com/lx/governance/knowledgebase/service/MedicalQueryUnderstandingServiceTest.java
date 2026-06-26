package com.lx.governance.knowledgebase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MedicalQueryUnderstandingServiceTest {

    @Test
    void shouldClassifyEmergencyRiskQueries() {
        MedicalQueryUnderstandingService service = new MedicalQueryUnderstandingService();

        MedicalQueryUnderstandingService.QueryUnderstanding understanding =
                service.understand("头晕伴胸痛需要马上去医院吗");

        assertEquals(MedicalQueryUnderstandingService.QueryType.EMERGENCY_RISK, understanding.getQueryType());
        assertTrue(understanding.getRewrittenQuery().contains("红旗症状"));
        assertTrue(understanding.getRewrittenQuery().contains("分诊"));
        assertTrue(understanding.getRewrittenQuery().contains("就医建议"));
    }

    @Test
    void shouldClassifyMedicationSafetyQueries() {
        MedicalQueryUnderstandingService service = new MedicalQueryUnderstandingService();

        MedicalQueryUnderstandingService.QueryUnderstanding understanding =
                service.understand("格华止怎么吃，有什么副作用");

        assertEquals(MedicalQueryUnderstandingService.QueryType.MEDICATION_SAFETY, understanding.getQueryType());
        assertTrue(understanding.getRewrittenQuery().contains("适应症"));
        assertTrue(understanding.getRewrittenQuery().contains("禁忌"));
        assertTrue(understanding.getRewrittenQuery().contains("注意事项"));
    }
}
