package com.lx.governance.profile.dto;

import java.util.List;

import com.lx.governance.profile.entity.UserProfileCandidateFieldEntity;
import com.lx.governance.profile.entity.UserProfileFieldConflictEntity;
import com.lx.governance.profile.entity.UserProfileFieldReviewAuditEntity;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ProfileCandidateFieldComparisonView {

    UserProfileCandidateFieldEntity candidate;
    List<UserProfileCandidateFieldEntity> peerCandidates;
    List<UserProfileFieldConflictEntity> conflicts;
    List<UserProfileFieldReviewAuditEntity> audits;
    String currentFormalValue;
    String latestResolvedFormalValue;
}
