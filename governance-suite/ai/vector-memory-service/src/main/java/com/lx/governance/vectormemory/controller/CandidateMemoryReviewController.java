package com.lx.governance.vectormemory.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.vectormemory.dto.CandidateMemoryComparisonView;
import com.lx.governance.vectormemory.dto.CandidateMemoryReviewRequest;
import com.lx.governance.vectormemory.entity.AiGroupMemoryConflictEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryReviewAuditEntity;
import com.lx.governance.vectormemory.application.governance.CandidateMemoryReviewService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/candidate-memory")
@RequiredArgsConstructor
public class CandidateMemoryReviewController {

    private final CandidateMemoryReviewService candidateMemoryReviewService;

    @PostMapping("/{id}/confirm")
    public ApiResponse<Boolean> confirmCandidate(@PathVariable("id") Long candidateId,
                                                 @RequestBody(required = false) CandidateMemoryReviewRequest request) {
        CandidateMemoryReviewRequest payload = request != null ? request : new CandidateMemoryReviewRequest();
        candidateMemoryReviewService.confirmCandidate(
                candidateId,
                payload.getReviewer(),
                payload.getResolutionReason(),
                payload.getRemarks());
        return ApiResponse.ok(true, Trace.getOrCreate());
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Boolean> rejectCandidate(@PathVariable("id") Long candidateId,
                                                @RequestBody(required = false) CandidateMemoryReviewRequest request) {
        CandidateMemoryReviewRequest payload = request != null ? request : new CandidateMemoryReviewRequest();
        candidateMemoryReviewService.rejectCandidate(
                candidateId,
                payload.getReviewer(),
                payload.getResolutionReason(),
                payload.getRemarks());
        return ApiResponse.ok(true, Trace.getOrCreate());
    }

    @GetMapping("/{id}/conflicts")
    public ApiResponse<java.util.List<AiGroupMemoryConflictEntity>> listConflicts(@PathVariable("id") Long candidateId) {
        return ApiResponse.ok(candidateMemoryReviewService.listConflicts(candidateId), Trace.getOrCreate());
    }

    @GetMapping("/{id}/audits")
    public ApiResponse<java.util.List<AiGroupMemoryReviewAuditEntity>> listAuditTrail(@PathVariable("id") Long candidateId) {
        return ApiResponse.ok(candidateMemoryReviewService.listAuditTrail(candidateId), Trace.getOrCreate());
    }

    @GetMapping("/{id}/comparison")
    public ApiResponse<CandidateMemoryComparisonView> comparison(@PathVariable("id") Long candidateId) {
        return ApiResponse.ok(candidateMemoryReviewService.buildComparisonView(candidateId), Trace.getOrCreate());
    }
}
