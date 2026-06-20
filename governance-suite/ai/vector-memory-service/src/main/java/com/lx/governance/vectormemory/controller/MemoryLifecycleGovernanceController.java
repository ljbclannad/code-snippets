package com.lx.governance.vectormemory.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.vectormemory.dto.CandidateMemoryLifecycleView;
import com.lx.governance.vectormemory.dto.MemoryGovernanceActionRequest;
import com.lx.governance.vectormemory.dto.MemoryGovernanceMergeRequest;
import com.lx.governance.vectormemory.dto.MemoryLifecycleHistoryView;
import com.lx.governance.vectormemory.dto.MemoryLifecycleReplayView;
import com.lx.governance.vectormemory.entity.AiGroupMemoryCandidateEntity;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.service.MemoryLifecycleGovernanceService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/memory-lifecycle")
@RequiredArgsConstructor
public class MemoryLifecycleGovernanceController {

    private final MemoryLifecycleGovernanceService governanceService;

    @GetMapping("/replay")
    public ApiResponse<MemoryLifecycleReplayView> replay(@RequestParam("groupId") String groupId,
                                                         @RequestParam("customerId") String customerId,
                                                         @RequestParam(value = "memoryType", required = false) String memoryType,
                                                         @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return ApiResponse.ok(governanceService.buildReplayView(groupId, customerId, memoryType, limit), Trace.getOrCreate());
    }

    @GetMapping("/candidates")
    public ApiResponse<List<AiGroupMemoryCandidateEntity>> listCandidates(@RequestParam("groupId") String groupId,
                                                                          @RequestParam("customerId") String customerId,
                                                                          @RequestParam(value = "status", required = false) List<String> statuses) {
        return ApiResponse.ok(governanceService.listCandidates(groupId, customerId, statuses), Trace.getOrCreate());
    }

    @GetMapping("/candidates/{id}")
    public ApiResponse<CandidateMemoryLifecycleView> candidateDetail(@PathVariable("id") Long candidateId) {
        return ApiResponse.ok(governanceService.getCandidateLifecycleView(candidateId), Trace.getOrCreate());
    }

    @GetMapping("/memories")
    public ApiResponse<List<AiGroupMemoryEntity>> listMemories(@RequestParam("groupId") String groupId,
                                                               @RequestParam("customerId") String customerId,
                                                               @RequestParam(value = "lifecycleStatus", required = false) String lifecycleStatus) {
        return ApiResponse.ok(governanceService.listMemories(groupId, customerId, lifecycleStatus), Trace.getOrCreate());
    }

    @GetMapping("/memories/{id}/history")
    public ApiResponse<MemoryLifecycleHistoryView> memoryHistory(@PathVariable("id") Long memoryId) {
        return ApiResponse.ok(governanceService.getMemoryHistory(memoryId), Trace.getOrCreate());
    }

    @PostMapping("/memories/{id}/archive")
    public ApiResponse<Boolean> archiveMemory(@PathVariable("id") Long memoryId,
                                              @RequestBody(required = false) MemoryGovernanceActionRequest request) {
        MemoryGovernanceActionRequest payload = request != null ? request : new MemoryGovernanceActionRequest();
        governanceService.archiveMemory(memoryId, payload.getReviewer(), payload.getReasonCode(), payload.getRemarks());
        return ApiResponse.ok(true, Trace.getOrCreate());
    }

    @PostMapping("/memories/{id}/restore")
    public ApiResponse<Boolean> restoreMemory(@PathVariable("id") Long memoryId,
                                              @RequestBody(required = false) MemoryGovernanceActionRequest request) {
        MemoryGovernanceActionRequest payload = request != null ? request : new MemoryGovernanceActionRequest();
        governanceService.restoreMemory(memoryId, payload.getReviewer(), payload.getReasonCode(), payload.getRemarks());
        return ApiResponse.ok(true, Trace.getOrCreate());
    }

    @PostMapping("/memories/{id}/delete")
    public ApiResponse<Boolean> deleteMemory(@PathVariable("id") Long memoryId,
                                             @RequestBody(required = false) MemoryGovernanceActionRequest request) {
        MemoryGovernanceActionRequest payload = request != null ? request : new MemoryGovernanceActionRequest();
        governanceService.deleteMemory(memoryId, payload.getReviewer(), payload.getReasonCode(), payload.getRemarks());
        return ApiResponse.ok(true, Trace.getOrCreate());
    }

    @PostMapping("/memories/merge")
    public ApiResponse<Boolean> mergeMemories(@RequestBody MemoryGovernanceMergeRequest request) {
        governanceService.mergeMemories(
                request.getSourceMemoryId(),
                request.getTargetMemoryId(),
                request.getReviewer(),
                request.getReasonCode(),
                request.getRemarks());
        return ApiResponse.ok(true, Trace.getOrCreate());
    }
}
