package com.lx.governance.knowledgebase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class MedicalMetadataNormalizerTest {

    @Test
    void shouldNormalizeAliasesAndBuildExtendedTags() {
        MedicalMetadataNormalizer normalizer = new MedicalMetadataNormalizer();

        MedicalMetadataNormalizer.NormalizedMedicalMetadata metadata = normalizer.normalize(
                "高血压运动管理",
                List.of("慢病管理", "运动"),
                "chronic_disease",
                "seed_library",
                "doctor_assistant",
                "high",
                "seed-import",
                "guideline",
                "hypertension",
                "adult",
                "中国",
                "2024",
                "2024-05-01");

        assertEquals("HIGH", metadata.getTrustLevel());
        assertEquals("chronic_disease", metadata.getBusinessDomain());
        assertTrue(metadata.getTags().contains("doc_type:指南"));
        assertTrue(metadata.getTags().contains("disease:高血压"));
        assertTrue(metadata.getTags().contains("population:成人"));
        assertTrue(metadata.getTags().contains("region:CN"));
        assertTrue(metadata.getTags().contains("version:2024"));
        assertTrue(metadata.getTags().contains("publish_date:2024-05-01"));
    }
}
