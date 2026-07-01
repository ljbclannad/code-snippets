package com.lx.governance.knowledgebase.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.lx.governance.knowledgebase.dto.SearchRequest;
import com.lx.governance.knowledgebase.dto.SearchResult;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MedicalRetrievalProfileService {

    private final MedicalMetadataNormalizer metadataNormalizer;

    public SearchRequest applyProfile(SearchRequest original) {
        if (original == null) {
            return null;
        }
        SearchRequest request = copyOf(original);
        applyProfileDefaults(request);
        request.setTags(buildAugmentedTags(request));
        request.setTrustLevel(normalizeTrustLevelIfPresent(request.getTrustLevel()));
        return request;
    }

    public List<SearchResult> applyPostFilters(SearchRequest request, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return results;
        }
        LocalDate publishDateFrom = parseDate(request != null ? request.getPublishDateFrom() : null);
        return results.stream()
                .filter(this::allowGovernedResult)
                .filter(result -> publishDateFrom == null || matchesPublishDateFrom(result, publishDateFrom))
                .toList();
    }

    private void applyProfileDefaults(SearchRequest request) {
        String profile = request.getRetrievalProfile();
        if (profile == null || profile.isBlank()) {
            return;
        }
        switch (profile.trim().toLowerCase(Locale.ROOT)) {
            case "patient_education" -> {
                if (isBlank(request.getApplicableScope())) {
                    request.setApplicableScope("patient_education");
                }
                addDocumentTypeIfAbsent(request, "患教材料");
                if (isBlank(request.getTrustLevel())) {
                    request.setTrustLevel("MEDIUM");
                }
            }
            case "doctor_workflow" -> {
                if (isBlank(request.getApplicableScope())) {
                    request.setApplicableScope("doctor_assistant");
                }
                addDocumentTypeIfAbsent(request, "SOP");
                addDocumentTypeIfAbsent(request, "指南");
                addDocumentTypeIfAbsent(request, "共识");
                if (isBlank(request.getTrustLevel())) {
                    request.setTrustLevel("HIGH");
                }
            }
            case "medication" -> {
                addDocumentTypeIfAbsent(request, "药品说明");
                if (isBlank(request.getTrustLevel())) {
                    request.setTrustLevel("HIGH");
                }
            }
            case "risk_triage" -> {
                addDocumentTypeIfAbsent(request, "风险预警规则");
                if (isBlank(request.getTrustLevel())) {
                    request.setTrustLevel("HIGH");
                }
            }
            default -> {
            }
        }
    }

    private List<String> buildAugmentedTags(SearchRequest request) {
        Set<String> tags = new LinkedHashSet<>();
        if (request.getTags() != null) {
            request.getTags().stream()
                    .filter(tag -> tag != null && !tag.isBlank())
                    .map(String::trim)
                    .forEach(tags::add);
        }
        addNormalizedTag(tags, "doc_type:", request.getDocumentTypes(), metadataNormalizer::normalizeDocumentType);
        addNormalizedTag(tags, "disease:", request.getDiseases(), metadataNormalizer::normalizeDisease);
        addNormalizedTag(tags, "population:", request.getPopulations(), metadataNormalizer::normalizePopulation);
        addNormalizedTag(tags, "region:", request.getRegions(), metadataNormalizer::normalizeRegion);
        addNormalizedTag(tags, "source_type:", request.getSourceTypes(), this::normalizeSourceType);
        if (!isBlank(request.getVersion())) {
            tags.add("version:" + request.getVersion().trim());
        }
        return new ArrayList<>(tags);
    }

    private void addNormalizedTag(Set<String> tags,
                                  String prefix,
                                  List<String> values,
                                  java.util.function.Function<String, String> normalizer) {
        if (values == null) {
            return;
        }
        values.stream()
                .map(value -> value == null ? null : normalizer.apply(value))
                .filter(value -> value != null && !value.isBlank())
                .map(value -> prefix + value)
                .forEach(tags::add);
    }

    private void addDocumentTypeIfAbsent(SearchRequest request, String documentType) {
        List<String> documentTypes = request.getDocumentTypes() != null
                ? new ArrayList<>(request.getDocumentTypes())
                : new ArrayList<>();
        String normalized = metadataNormalizer.normalizeDocumentType(documentType);
        if (normalized != null && !documentTypes.contains(normalized)) {
            documentTypes.add(normalized);
        }
        request.setDocumentTypes(documentTypes);
    }

    private boolean matchesPublishDateFrom(SearchResult result, LocalDate publishDateFrom) {
        if (!allowGovernedResult(result)) {
            return false;
        }
        String tags = result.getTags();
        if (tags == null || tags.isBlank()) {
            return false;
        }
        for (String tag : tags.split(",")) {
            String trimmed = tag.trim();
            if (trimmed.startsWith("publish_date:")) {
                LocalDate publishDate = parseDate(trimmed.substring("publish_date:".length()));
                return publishDate != null && !publishDate.isBefore(publishDateFrom);
            }
        }
        return false;
    }

    private String normalizeTrustLevelIfPresent(String trustLevel) {
        return isBlank(trustLevel) ? trustLevel : metadataNormalizer.normalizeTrustLevel(trustLevel);
    }

    private SearchRequest copyOf(SearchRequest original) {
        SearchRequest copied = new SearchRequest();
        copied.setQuery(original.getQuery());
        copied.setTopK(original.getTopK());
        copied.setSimilarityThreshold(original.getSimilarityThreshold());
        copied.setRerank(original.getRerank());
        copied.setHybridSearch(original.getHybridSearch());
        copied.setUseRewrite(original.getUseRewrite());
        copied.setTitleKeyword(original.getTitleKeyword());
        copied.setTags(original.getTags() != null ? new ArrayList<>(original.getTags()) : null);
        copied.setRetrievalProfile(original.getRetrievalProfile());
        copied.setDocumentTypes(original.getDocumentTypes() != null ? new ArrayList<>(original.getDocumentTypes()) : null);
        copied.setDiseases(original.getDiseases() != null ? new ArrayList<>(original.getDiseases()) : null);
        copied.setPopulations(original.getPopulations() != null ? new ArrayList<>(original.getPopulations()) : null);
        copied.setRegions(original.getRegions() != null ? new ArrayList<>(original.getRegions()) : null);
        copied.setSourceTypes(original.getSourceTypes() != null ? new ArrayList<>(original.getSourceTypes()) : null);
        copied.setVersion(original.getVersion());
        copied.setPublishDateFrom(original.getPublishDateFrom());
        copied.setBusinessDomain(original.getBusinessDomain());
        copied.setSource(original.getSource());
        copied.setApplicableScope(original.getApplicableScope());
        copied.setTrustLevel(original.getTrustLevel());
        copied.setDocIds(original.getDocIds() != null ? new ArrayList<>(original.getDocIds()) : null);
        return copied;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private LocalDate parseDate(String raw) {
        try {
            return isBlank(raw) ? null : LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private boolean allowGovernedResult(SearchResult result) {
        String reviewStatus = extractTagValue(result != null ? result.getTags() : null, "review_status:");
        String invalidated = extractTagValue(result != null ? result.getTags() : null, "invalidated:");
        if ("pending".equalsIgnoreCase(reviewStatus) || "rejected".equalsIgnoreCase(reviewStatus)) {
            return false;
        }
        return !"true".equalsIgnoreCase(invalidated);
    }

    private String normalizeSourceType(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "guideline", "指南", "学会指南" -> "guideline";
            case "consensus", "共识", "专家共识" -> "consensus";
            case "hospital", "医院", "医院科普" -> "hospital";
            case "drug_label", "药品说明", "说明书" -> "drug_label";
            case "faq", "faq文档", "问答" -> "faq";
            case "web", "网页", "官网文章" -> "web";
            case "rule", "风险规则", "预警规则" -> "rule";
            default -> normalized.replace(' ', '_');
        };
    }

    private String extractTagValue(String tags, String prefix) {
        if (tags == null || tags.isBlank()) {
            return null;
        }
        for (String tag : tags.split(",")) {
            String trimmed = tag.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length());
            }
        }
        return null;
    }
}
