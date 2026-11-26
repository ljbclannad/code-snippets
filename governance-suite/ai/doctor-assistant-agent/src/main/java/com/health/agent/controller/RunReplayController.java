package com.health.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.health.agent.service.agent.DoctorRunReplayService;
import com.lx.governance.agentplatform.model.RunReplayView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 平台 run 回放查询接口。
 */
@RestController
@RequestMapping("/api/runs")
@Tag(name = "Run 回放", description = "平台 run replay 查询接口")
public class RunReplayController {

    private final DoctorRunReplayService doctorRunReplayService;

    public RunReplayController(DoctorRunReplayService doctorRunReplayService) {
        this.doctorRunReplayService = doctorRunReplayService;
    }

    @GetMapping("/{runId}/replay")
    @Operation(summary = "按 runId 回放平台链路")
    public ResponseEntity<RunReplayView> replay(
            @Parameter(description = "平台 runId") @PathVariable("runId") String runId) {
        return ResponseEntity.ok(doctorRunReplayService.getReplayByRunId(runId));
    }
}
