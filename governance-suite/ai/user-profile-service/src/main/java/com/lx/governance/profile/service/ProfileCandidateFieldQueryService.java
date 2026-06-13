package com.lx.governance.profile.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.lx.governance.profile.entity.UserProfileCandidateFieldEntity;
import com.lx.governance.profile.entity.UserProfileFieldConflictEntity;
import com.lx.governance.profile.mapper.UserProfileCandidateFieldMapper;
import com.lx.governance.profile.mapper.UserProfileFieldConflictMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProfileCandidateFieldQueryService {

    private final UserProfileCandidateFieldMapper candidateFieldMapper;
    private final UserProfileFieldConflictMapper conflictMapper;

    public List<UserProfileCandidateFieldEntity> listCandidates(String customerId) {
        return candidateFieldMapper.selectActiveByCustomerId(customerId);
    }

    public List<UserProfileFieldConflictEntity> listConflicts(Long candidateFieldId) {
        return conflictMapper.selectByCandidateFieldId(candidateFieldId);
    }
}
