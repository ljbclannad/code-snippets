package com.lx.governance.knowledgebase.service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.knowledgebase.dto.SearchRequest;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MedicalSearchTermNormalizer {

    private final Map<String, String> aliasToCanonical;

    public MedicalSearchTermNormalizer(ObjectMapper objectMapper) {
        this.aliasToCanonical = loadMappings(objectMapper);
    }

    public SearchRequest normalizeRequest(SearchRequest original) {
        if (original == null) {
            return null;
        }
        SearchRequest normalized = new SearchRequest();
        normalized.setQuery(expandQuery(original.getQuery()));
        normalized.setTopK(original.getTopK());
        normalized.setSimilarityThreshold(original.getSimilarityThreshold());
        normalized.setRerank(original.getRerank());
        normalized.setHybridSearch(original.getHybridSearch());
        normalized.setUseRewrite(original.getUseRewrite());
        normalized.setTitleKeyword(normalizeTerm(original.getTitleKeyword()));
        normalized.setTags(normalizeTags(original.getTags()));
        normalized.setBusinessDomain(original.getBusinessDomain());
        normalized.setSource(original.getSource());
        normalized.setApplicableScope(original.getApplicableScope());
        normalized.setTrustLevel(original.getTrustLevel());
        normalized.setDocIds(original.getDocIds());
        return normalized;
    }

    public String expandQuery(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }
        String normalized = query.trim();
        Set<String> additions = new LinkedHashSet<>();
        aliasToCanonical.forEach((alias, canonical) -> {
            if (containsIgnoreCase(normalized, alias) && !containsIgnoreCase(normalized, canonical)) {
                additions.add(canonical);
            }
        });
        if (additions.isEmpty()) {
            return normalized;
        }
        return normalized + " " + String.join(" ", additions);
    }

    public List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return tags;
        }
        List<String> normalized = new ArrayList<>(tags.size());
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                continue;
            }
            normalized.add(normalizeTerm(tag));
        }
        return normalized;
    }

    public String normalizeTerm(String term) {
        if (term == null || term.isBlank()) {
            return term;
        }
        String trimmed = term.trim();
        String canonical = aliasToCanonical.get(trimmed);
        if (canonical != null) {
            return canonical;
        }
        return aliasToCanonical.getOrDefault(trimmed.toLowerCase(Locale.ROOT), trimmed);
    }

    private boolean containsIgnoreCase(String source, String target) {
        return source.toLowerCase(Locale.ROOT).contains(target.toLowerCase(Locale.ROOT));
    }

    private Map<String, String> loadMappings(ObjectMapper objectMapper) {
        try (InputStream inputStream = new ClassPathResource("medical/medical-term-mapping.json").getInputStream()) {
            MedicalTermMapping mapping = objectMapper.readValue(inputStream, MedicalTermMapping.class);
            Map<String, String> aliases = new LinkedHashMap<>();
            registerAliases(aliases, mapping.diseaseAliases());
            registerAliases(aliases, mapping.medicationAliases());
            registerAliases(aliases, mapping.labTestAliases());
            registerAliases(aliases, mapping.symptomAliases());
            return aliases;
        } catch (Exception e) {
            log.warn("加载医疗术语映射失败，回退空词表: {}", e.getMessage());
            return Map.of();
        }
    }

    private void registerAliases(Map<String, String> aliasMap, Map<String, List<String>> aliasesByCanonical) {
        if (aliasesByCanonical == null) {
            return;
        }
        aliasesByCanonical.forEach((canonical, aliases) -> {
            if (canonical == null || canonical.isBlank()) {
                return;
            }
            aliasMap.put(canonical.trim(), canonical.trim());
            aliasMap.put(canonical.trim().toLowerCase(Locale.ROOT), canonical.trim());
            if (aliases == null) {
                return;
            }
            aliases.stream()
                    .filter(alias -> alias != null && !alias.isBlank())
                    .map(String::trim)
                    .forEach(alias -> {
                        aliasMap.put(alias, canonical.trim());
                        aliasMap.put(alias.toLowerCase(Locale.ROOT), canonical.trim());
                    });
        });
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MedicalTermMapping(
            Map<String, List<String>> diseaseAliases,
            Map<String, List<String>> medicationAliases,
            Map<String, List<String>> labTestAliases,
            Map<String, List<String>> symptomAliases) {
    }
}
