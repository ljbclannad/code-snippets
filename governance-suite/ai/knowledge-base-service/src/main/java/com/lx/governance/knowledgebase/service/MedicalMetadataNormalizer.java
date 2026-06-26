package com.lx.governance.knowledgebase.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class MedicalMetadataNormalizer {

    private static final Map<String, String> DISEASE_ALIASES = Map.ofEntries(
            Map.entry("高血压", "高血压"),
            Map.entry("血压高", "高血压"),
            Map.entry("hypertension", "高血压"),
            Map.entry("2型糖尿病", "2型糖尿病"),
            Map.entry("糖尿病2型", "2型糖尿病"),
            Map.entry("type 2 diabetes", "2型糖尿病"),
            Map.entry("肥胖", "肥胖"),
            Map.entry("obesity", "肥胖"));

    private static final Map<String, String> POPULATION_ALIASES = Map.ofEntries(
            Map.entry("成人", "成人"),
            Map.entry("成年人", "成人"),
            Map.entry("adult", "成人"),
            Map.entry("老年", "老年人"),
            Map.entry("老年人", "老年人"),
            Map.entry("elderly", "老年人"),
            Map.entry("儿童", "儿童"),
            Map.entry("child", "儿童"));

    private static final Map<String, String> REGION_ALIASES = Map.ofEntries(
            Map.entry("cn", "CN"),
            Map.entry("china", "CN"),
            Map.entry("中国", "CN"),
            Map.entry("mainland china", "CN"),
            Map.entry("global", "GLOBAL"),
            Map.entry("国际", "GLOBAL"));

    private static final Map<String, String> DOCUMENT_TYPE_ALIASES = Map.ofEntries(
            Map.entry("guideline", "指南"),
            Map.entry("指南", "指南"),
            Map.entry("consensus", "共识"),
            Map.entry("共识", "共识"),
            Map.entry("drug_instruction", "药品说明"),
            Map.entry("药品说明", "药品说明"),
            Map.entry("药品说明书", "药品说明"),
            Map.entry("patient_education", "患教材料"),
            Map.entry("患教材料", "患教材料"),
            Map.entry("患教", "患教材料"),
            Map.entry("sop", "SOP"),
            Map.entry("workflow_sop", "SOP"),
            Map.entry("risk_rule", "风险预警规则"),
            Map.entry("risk_alert_rule", "风险预警规则"),
            Map.entry("风险预警规则", "风险预警规则"));

    public NormalizedMedicalMetadata normalize(
            String title,
            List<String> tags,
            String businessDomain,
            String source,
            String applicableScope,
            String trustLevel,
            String createdBy,
            String documentType,
            String disease,
            String population,
            String region,
            String version,
            String publishDate) {

        String normalizedDocumentType = normalizeDocumentType(documentType);
        String normalizedDisease = normalizeAlias(disease, DISEASE_ALIASES);
        String normalizedPopulation = normalizeAlias(population, POPULATION_ALIASES);
        String normalizedRegion = normalizeAlias(region, REGION_ALIASES);

        Set<String> normalizedTags = new LinkedHashSet<>();
        normalizedTags.add("medical");
        if (normalizedDocumentType != null) {
            normalizedTags.add("doc_type:" + normalizedDocumentType);
        }
        if (normalizedDisease != null) {
            normalizedTags.add("disease:" + normalizedDisease);
        }
        if (normalizedPopulation != null) {
            normalizedTags.add("population:" + normalizedPopulation);
        }
        if (normalizedRegion != null) {
            normalizedTags.add("region:" + normalizedRegion);
        }
        if (version != null && !version.isBlank()) {
            normalizedTags.add("version:" + version.trim());
        }
        if (publishDate != null && !publishDate.isBlank()) {
            normalizedTags.add("publish_date:" + publishDate.trim());
        }
        if (tags != null) {
            tags.stream()
                    .filter(tag -> tag != null && !tag.isBlank())
                    .map(String::trim)
                    .forEach(normalizedTags::add);
        }

        Map<String, String> normalizedFields = new LinkedHashMap<>();
        normalizedFields.put("documentType", normalizedDocumentType);
        normalizedFields.put("disease", normalizedDisease);
        normalizedFields.put("population", normalizedPopulation);
        normalizedFields.put("region", normalizedRegion);
        normalizedFields.put("version", blankToNull(version));
        normalizedFields.put("publishDate", blankToNull(publishDate));

        return NormalizedMedicalMetadata.builder()
                .title(title)
                .tags(new ArrayList<>(normalizedTags))
                .businessDomain(blankToDefault(businessDomain, "medical"))
                .source(blankToDefault(source, "local_seed_library"))
                .applicableScope(blankToDefault(applicableScope, "doctor_assistant"))
                .trustLevel(normalizeTrustLevel(trustLevel))
                .createdBy(blankToDefault(createdBy, "kb-local-batch-import"))
                .normalizedFields(normalizedFields)
                .build();
    }

    public String normalizeDocumentType(String documentType) {
        return normalizeAlias(documentType, DOCUMENT_TYPE_ALIASES);
    }

    public String normalizeTrustLevel(String trustLevel) {
        String value = blankToDefault(trustLevel, "MEDIUM").toUpperCase(Locale.ROOT);
        return switch (value) {
            case "HIGH", "MEDIUM", "LOW" -> value;
            default -> "MEDIUM";
        };
    }

    public String normalizeDisease(String disease) {
        return normalizeAlias(disease, DISEASE_ALIASES);
    }

    public String normalizePopulation(String population) {
        return normalizeAlias(population, POPULATION_ALIASES);
    }

    public String normalizeRegion(String region) {
        return normalizeAlias(region, REGION_ALIASES);
    }

    private String normalizeAlias(String value, Map<String, String> aliases) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        String normalized = aliases.get(trimmed);
        if (normalized != null) {
            return normalized;
        }
        return aliases.get(trimmed.toLowerCase(Locale.ROOT));
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @lombok.Builder
    @lombok.Value
    public static class NormalizedMedicalMetadata {
        String title;
        List<String> tags;
        String businessDomain;
        String source;
        String applicableScope;
        String trustLevel;
        String createdBy;
        Map<String, String> normalizedFields;
    }
}
