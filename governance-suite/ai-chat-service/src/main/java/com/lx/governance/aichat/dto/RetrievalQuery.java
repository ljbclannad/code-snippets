package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalQuery {

    private String originalMessage;
    private String rewrittenQuery;
    private String rewriteSource;
    private String rewriteReason;
}
