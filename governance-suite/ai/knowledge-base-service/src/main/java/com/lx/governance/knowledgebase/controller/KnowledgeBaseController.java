package com.lx.governance.knowledgebase.controller;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.knowledgebase.dto.CreateKnowledgeBaseRequest;
import com.lx.governance.knowledgebase.dto.KnowledgeBaseResponse;
import com.lx.governance.knowledgebase.entity.KnowledgeBase;
import com.lx.governance.knowledgebase.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 知识库管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 创建知识库
     */
    @PostMapping
    public ApiResponse<KnowledgeBaseResponse> createKnowledgeBase(
            @Validated @RequestBody CreateKnowledgeBaseRequest request) {

        log.info("创建知识库请求: {}", request.getName());

        KnowledgeBase kb = knowledgeBaseService.createKnowledgeBase(
                request.getName(),
                request.getDescription(),
                request.getChunkSize(),
                request.getChunkOverlap(),
                request.getSemanticThreshold(),
                request.getCreatedBy()
        );

        KnowledgeBaseResponse response = new KnowledgeBaseResponse();
        BeanUtils.copyProperties(kb, response);

        return ApiResponse.ok(response, null);
    }

    /**
     * 查询知识库详情
     */
    @GetMapping("/{kbId}")
    public ApiResponse<KnowledgeBaseResponse> getKnowledgeBase(@PathVariable String kbId) {
        log.info("查询知识库: {}", kbId);

        KnowledgeBase kb = knowledgeBaseService.getByKbId(kbId);
        if (kb == null) {
            return ApiResponse.fail("KB_NOT_FOUND", "知识库不存在", null);
        }

        KnowledgeBaseResponse response = new KnowledgeBaseResponse();
        BeanUtils.copyProperties(kb, response);

        return ApiResponse.ok(response, null);
    }

    /**
     * 删除知识库
     */
    @DeleteMapping("/{kbId}")
    public ApiResponse<Void> deleteKnowledgeBase(@PathVariable String kbId) {
        log.info("删除知识库: {}", kbId);

        knowledgeBaseService.deleteKnowledgeBase(kbId);
        return ApiResponse.ok(null, null);
    }
}
