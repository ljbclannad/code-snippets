package com.lx.governance.profile.service;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProfileCandidateFieldReviewService {

    private final ProfileCandidateFieldService profileCandidateFieldService;

    public void confirmCandidateField(Long id, String reviewer) {
        profileCandidateFieldService.confirmCandidateField(id, reviewer);
    }

    public void rejectCandidateField(Long id, String reviewer) {
        profileCandidateFieldService.rejectCandidateField(id, reviewer);
    }
}
