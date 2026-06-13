package com.lx.governance.knowledgebase.dto;

import lombok.Data;

import java.util.List;

@Data
public class RerankResult {

    private List<RerankItem> items;
}
