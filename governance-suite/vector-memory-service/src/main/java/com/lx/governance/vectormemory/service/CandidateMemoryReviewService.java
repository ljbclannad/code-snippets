package com.lx.governance.vectormemory.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.lx.governance.vectormemory.dto.CandidateMemoryComparisonView;
import com.lx.governance.vectormemory.entity.AiGroupMemoryCandidateEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryConflictEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryReviewAuditEntity;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryCandidateMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryConflictMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryReviewAuditMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CandidateMemoryReviewService {

    private final CandidateMemoryService candidateMemoryService;
    private final AiGroupMemoryCandidateMapper candidateMapper;
    private final AiGroupMemoryConflictMapper conflictMapper;
    private final AiGroupMemoryMapper confirmedMemoryMapper;
    private final AiGroupMemoryReviewAuditMapper reviewAuditMapper;

    public void confirmCandidate(Long candidateId, String reviewer, String resolutionReason, String remarks) {
        candidateMemoryService.confirmCandidate(candidateId, reviewer, resolutionReason, remarks);
    }

    public void rejectCandidate(Long candidateId, String reviewer, String resolutionReason, String remarks) {
        candidateMemoryService.rejectCandidate(candidateId, reviewer, resolutionReason, remarks);
    }

    public List<AiGroupMemoryConflictEntity> listConflicts(Long candidateId) {
        return conflictMapper.selectByCandidateId(candidateId);
    }

    public List<AiGroupMemoryReviewAuditEntity> listAuditTrail(Long candidateId) {
        return reviewAuditMapper.selectByCandidateId(candidateId);
    }

    public CandidateMemoryComparisonView buildComparisonView(Long candidateId) {
        AiGroupMemoryCandidateEntity candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate memory not found: " + candidateId);
        }

        List<AiGroupMemoryCandidateEntity> peerCandidates = candidateMapper
                .selectActiveByCustomerId(candidate.getCustomerId(), candidate.getGroupId())
                .stream()
                .filter(item -> item.getId() != null)
                .filter(item -> !candidateId.equals(item.getId()))
                .filter(item -> candidate.getMemoryType().equals(item.getMemoryType()))
                .filter(item -> candidate.getNormalizedKey() != null && candidate.getNormalizedKey().equals(item.getNormalizedKey()))
                .toList();

        AiGroupMemoryEntity relatedFormalMemory = candidate.getConfirmedMemoryId() != null
                ? confirmedMemoryMapper.selectById(candidate.getConfirmedMemoryId())
                : null;

        return CandidateMemoryComparisonView.builder()
                .candidate(candidate)
                .peerCandidates(peerCandidates)
                .conflicts(conflictMapper.selectByCandidateId(candidateId))
                .audits(reviewAuditMapper.selectByCandidateId(candidateId))
                .relatedFormalMemory(relatedFormalMemory)
                .build();
    }
}
