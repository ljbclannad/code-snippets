package com.lx.governance.knowledgebase.dto;

import lombok.Data;

@Data
public class SemanticDedupResult {

    private boolean duplicate;

    private double score;

    private String reason;

    private String matchedDocId;
}
