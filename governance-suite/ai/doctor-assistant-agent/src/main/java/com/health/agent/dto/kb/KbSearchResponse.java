package com.health.agent.dto.kb;

import java.util.List;

import lombok.Data;

@Data
public class KbSearchResponse {

    private Boolean success;
    private List<KbSearchResult> results;
    private Integer totalCount;
}
