package com.lx.governance.aichat.dto.kb;

import java.util.List;

import lombok.Data;

@Data
public class KbSearchRequest {
    private String query;
    private Integer topK;
    private Double similarityThreshold;
    private Boolean rerank;
    private Boolean hybridSearch;
    private Boolean useRewrite;
    private String retrievalProfile;
    private List<String> sourceTypes;
    private String businessDomain;
    private String applicableScope;
    private String trustLevel;
}
