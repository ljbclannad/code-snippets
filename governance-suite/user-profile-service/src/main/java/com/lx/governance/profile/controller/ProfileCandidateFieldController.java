package com.lx.governance.profile.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.profile.service.ProfileCandidateFieldReviewService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/profile-candidate-field")
@RequiredArgsConstructor
public class ProfileCandidateFieldController {

    private final ProfileCandidateFieldReviewService reviewService;

    @PostMapping("/{id}/confirm")
    public ApiResponse<Boolean> confirm(@PathVariable("id") Long id,
                                        @RequestParam(defaultValue = "SYSTEM") String reviewer) {
        reviewService.confirmCandidateField(id, reviewer);
        return ApiResponse.ok(true, null);
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Boolean> reject(@PathVariable("id") Long id,
                                       @RequestParam(defaultValue = "SYSTEM") String reviewer) {
        reviewService.rejectCandidateField(id, reviewer);
        return ApiResponse.ok(true, null);
    }
}
