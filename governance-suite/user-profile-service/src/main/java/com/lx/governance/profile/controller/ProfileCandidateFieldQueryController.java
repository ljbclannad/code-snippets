package com.lx.governance.profile.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.profile.entity.UserProfileCandidateFieldEntity;
import com.lx.governance.profile.entity.UserProfileFieldConflictEntity;
import com.lx.governance.profile.service.ProfileCandidateFieldQueryService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/profile-candidate-field/query")
@RequiredArgsConstructor
public class ProfileCandidateFieldQueryController {

    private final ProfileCandidateFieldQueryService queryService;

    @GetMapping
    public ApiResponse<List<UserProfileCandidateFieldEntity>> listCandidates(@RequestParam String customerId) {
        return ApiResponse.ok(queryService.listCandidates(customerId), null);
    }

    @GetMapping("/{id}/conflicts")
    public ApiResponse<List<UserProfileFieldConflictEntity>> listConflicts(@PathVariable("id") Long id) {
        return ApiResponse.ok(queryService.listConflicts(id), null);
    }
}
