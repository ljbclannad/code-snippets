package com.lx.governance.knowledgebase.dto;

import lombok.Data;

import java.util.List;

@Data
public class QueryRewriteResult {

    private String rewrittenQuery;

    private List<String> expansionTerms;
}
