package com.lx.governance.knowledgebase.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class MetadataExtractionResult {

    private String summary;

    private List<String> keywords;

    private List<String> suggestedTags;

    private Map<String, Object> extendedMetadata;
}
