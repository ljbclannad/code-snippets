package com.health.agent.dto.kb;

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
    private java.util.List<String> sourceTypes;
    private String businessDomain;
    private String applicableScope;
    private String trustLevel;
}
