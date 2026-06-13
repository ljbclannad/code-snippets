package com.lx.governance.aichat.dto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalRewriteResult {

    private String rewrittenQuery;

    @Builder.Default
    private List<String> keywords = new ArrayList<>();

    @Builder.Default
    private Set<String> suggestedMemoryTypes = new LinkedHashSet<>();

    private Integer suggestedRecentDays;

    private Boolean contextDependent;

    private String rawResponse;
}
