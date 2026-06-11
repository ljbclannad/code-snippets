package com.lx.governance.knowledgebase.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class UploadDocumentRequest {

    private MultipartFile file;

    @NotBlank(message = "标题不能为空")
    private String title;

    @NotBlank(message = "标签不能为空")
    private String tags;

    @NotBlank(message = "业务域不能为空")
    private String businessDomain;

    @NotBlank(message = "来源不能为空")
    private String source;

    @NotBlank(message = "适用范围不能为空")
    private String applicableScope;

    @NotBlank(message = "可信级别不能为空")
    private String trustLevel;

    private String createdBy;
}
