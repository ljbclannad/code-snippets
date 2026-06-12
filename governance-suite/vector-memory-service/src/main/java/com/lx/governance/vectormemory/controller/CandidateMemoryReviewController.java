package com.lx.governance.vectormemory.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.vectormemory.service.CandidateMemoryReviewService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/candidate-memory")
@RequiredArgsConstructor
public class CandidateMemoryReviewController {

    private final CandidateMemoryReviewService candidateMemoryReviewService;

    @PostMapping("/{id}/confirm")
    public ApiResponse<Boolean> confirmCandidate(@PathVariable("id") Long candidateId,
                                                 @RequestParam(defaultValue = "SYSTEM") String reviewer) {
        candidateMemoryReviewService.confirmCandidate(candidateId, reviewer);
        return ApiResponse.ok(true, Trace.getOrCreate());
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Boolean> rejectCandidate(@PathVariable("id") Long candidateId,
                                                @RequestParam(defaultValue = "SYSTEM") String reviewer) {
        candidateMemoryReviewService.rejectCandidate(candidateId, reviewer);
        return ApiResponse.ok(true, Trace.getOrCreate());
    }
}
