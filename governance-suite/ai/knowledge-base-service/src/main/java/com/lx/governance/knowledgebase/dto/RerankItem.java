package com.lx.governance.knowledgebase.dto;

import lombok.Data;

@Data
public class RerankItem {

    private String chunkId;

    private double score;

    private String reason;
}
