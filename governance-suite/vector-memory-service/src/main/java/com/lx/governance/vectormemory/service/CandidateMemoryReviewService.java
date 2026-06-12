package com.lx.governance.vectormemory.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.lx.governance.vectormemory.mapper.AiGroupMemoryCandidateMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CandidateMemoryReviewService {

    private final AiGroupMemoryCandidateMapper candidateMapper;
    private final CandidateMemoryService candidateMemoryService;

    public void confirmCandidate(Long candidateId, String reviewer) {
        candidateMemoryService.confirmCandidate(candidateId, reviewer);
    }

    public void rejectCandidate(Long candidateId, String reviewer) {
        candidateMemoryService.rejectCandidate(candidateId, reviewer);
    }
}
