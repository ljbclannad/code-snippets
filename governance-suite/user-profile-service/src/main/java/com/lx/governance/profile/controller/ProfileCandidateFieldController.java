package com.lx.governance.profile.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.profile.dto.ProfileCandidateFieldComparisonView;
import com.lx.governance.profile.dto.ProfileCandidateFieldReviewRequest;
import com.lx.governance.profile.entity.UserProfileFieldConflictEntity;
import com.lx.governance.profile.entity.UserProfileFieldReviewAuditEntity;
import com.lx.governance.profile.service.ProfileCandidateFieldReviewService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/profile-candidate-field")
@RequiredArgsConstructor
public class ProfileCandidateFieldController {

    private final ProfileCandidateFieldReviewService reviewService;

    @PostMapping("/{id}/confirm")
    public ApiResponse<Boolean> confirm(@PathVariable("id") Long id,
                                        @RequestBody(required = false) ProfileCandidateFieldReviewRequest request) {
        ProfileCandidateFieldReviewRequest payload = request != null ? request : new ProfileCandidateFieldReviewRequest();
        reviewService.confirmCandidateField(
                id,
                payload.getReviewer(),
                payload.getResolutionReason(),
                payload.getRemarks());
        return ApiResponse.ok(true, null);
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Boolean> reject(@PathVariable("id") Long id,
                                       @RequestBody(required = false) ProfileCandidateFieldReviewRequest request) {
        ProfileCandidateFieldReviewRequest payload = request != null ? request : new ProfileCandidateFieldReviewRequest();
        reviewService.rejectCandidateField(
                id,
                payload.getReviewer(),
                payload.getResolutionReason(),
                payload.getRemarks());
        return ApiResponse.ok(true, null);
    }

    @GetMapping("/{id}/conflicts")
    public ApiResponse<java.util.List<UserProfileFieldConflictEntity>> listConflicts(@PathVariable("id") Long id) {
        return ApiResponse.ok(reviewService.listConflicts(id), null);
    }

    @GetMapping("/{id}/audits")
    public ApiResponse<java.util.List<UserProfileFieldReviewAuditEntity>> listAudits(@PathVariable("id") Long id) {
        return ApiResponse.ok(reviewService.listAuditTrail(id), null);
    }

    @GetMapping("/{id}/comparison")
    public ApiResponse<ProfileCandidateFieldComparisonView> comparison(@PathVariable("id") Long id) {
        return ApiResponse.ok(reviewService.buildComparisonView(id), null);
    }
}
