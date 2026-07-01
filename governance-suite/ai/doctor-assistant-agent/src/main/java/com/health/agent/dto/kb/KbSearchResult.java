package com.health.agent.dto.kb;

import java.util.Map;

import lombok.Data;

@Data
public class KbSearchResult {

    private String chunkId;
    private String docId;
    private String title;
    private String tags;
    private String businessDomain;
    private String source;
    private String applicableScope;
    private String trustLevel;
    private String content;
    private Double score;
    private Map<String, Object> metadata;
    private String hitReason;
}
