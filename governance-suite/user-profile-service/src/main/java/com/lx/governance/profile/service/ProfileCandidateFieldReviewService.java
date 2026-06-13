package com.lx.governance.profile.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.lx.governance.profile.dto.ProfileCandidateFieldComparisonView;
import com.lx.governance.profile.entity.UserProfileCandidateFieldEntity;
import com.lx.governance.profile.entity.UserProfileFieldConflictEntity;
import com.lx.governance.profile.entity.UserProfileFieldReviewAuditEntity;
import com.lx.governance.profile.mapper.UserProfileCandidateFieldMapper;
import com.lx.governance.profile.mapper.UserProfileFieldConflictMapper;
import com.lx.governance.profile.mapper.UserProfileFieldReviewAuditMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProfileCandidateFieldReviewService {

    private final ProfileCandidateFieldService profileCandidateFieldService;
    private final UserProfileCandidateFieldMapper candidateFieldMapper;
    private final UserProfileFieldConflictMapper conflictMapper;
    private final UserProfileFieldReviewAuditMapper reviewAuditMapper;

    public void confirmCandidateField(Long id, String reviewer, String resolutionReason, String remarks) {
        profileCandidateFieldService.confirmCandidateField(id, reviewer, resolutionReason, remarks);
    }

    public void rejectCandidateField(Long id, String reviewer, String resolutionReason, String remarks) {
        profileCandidateFieldService.rejectCandidateField(id, reviewer, resolutionReason, remarks);
    }

    public List<UserProfileFieldConflictEntity> listConflicts(Long candidateFieldId) {
        return conflictMapper.selectByCandidateFieldId(candidateFieldId);
    }

    public List<UserProfileFieldReviewAuditEntity> listAuditTrail(Long candidateFieldId) {
        return reviewAuditMapper.selectByCandidateFieldId(candidateFieldId);
    }

    public ProfileCandidateFieldComparisonView buildComparisonView(Long candidateFieldId) {
        UserProfileCandidateFieldEntity candidate = candidateFieldMapper.selectById(candidateFieldId);
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate field not found: " + candidateFieldId);
        }

        List<UserProfileFieldReviewAuditEntity> audits = reviewAuditMapper.selectByCandidateFieldId(candidateFieldId);
        String latestResolvedFormalValue = audits.stream()
                .map(UserProfileFieldReviewAuditEntity::getRelatedFormalValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);

        return ProfileCandidateFieldComparisonView.builder()
                .candidate(candidate)
                .peerCandidates(candidateFieldMapper.selectActiveByCustomerId(candidate.getCustomerId()).stream()
                        .filter(item -> item.getId() != null)
                        .filter(item -> !candidateFieldId.equals(item.getId()))
                        .filter(item -> candidate.getFieldPath().equals(item.getFieldPath()))
                        .toList())
                .conflicts(conflictMapper.selectByCandidateFieldId(candidateFieldId))
                .audits(audits)
                .currentFormalValue(profileCandidateFieldService.getCurrentFormalValue(
                        candidate.getCustomerId(), candidate.getFieldPath()))
                .latestResolvedFormalValue(latestResolvedFormalValue)
                .build();
    }
}
