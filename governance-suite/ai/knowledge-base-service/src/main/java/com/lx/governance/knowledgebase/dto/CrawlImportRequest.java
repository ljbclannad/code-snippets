package com.lx.governance.knowledgebase.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CrawlImportRequest {

    @NotBlank(message = "入口 URL 不能为空")
    private String seedUrl;

    @Min(value = 1, message = "抓取页数不能小于 1")
    @Max(value = 50, message = "抓取页数不能大于 50")
    private Integer maxPages = 10;

    private String businessDomain = "medical";

    private String applicableScope = "doctor_assistant";

    private String trustLevel = "HIGH";

    private String source = "web_crawler";

    private String titlePrefix = "医疗知识抓取";

    private String createdBy;

    private List<String> tags;

    private List<String> allowedDomains;

    private Boolean sameHostOnly = true;

    private Boolean includePathPrefixOnly = true;
}
