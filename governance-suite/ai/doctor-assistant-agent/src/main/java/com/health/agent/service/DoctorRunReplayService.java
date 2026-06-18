package com.health.agent.service;

import org.springframework.stereotype.Service;

import com.lx.governance.agentplatform.model.RunReplayView;
import com.lx.governance.agentplatform.runtime.RunReplayService;

/**
 * doctor 场景的 run 回放查询入口。
 */
@Service
public class DoctorRunReplayService {

    private final RunReplayService runReplayService;

    public DoctorRunReplayService(RunReplayService runReplayService) {
        this.runReplayService = runReplayService;
    }

    public RunReplayView getReplayByRunId(String runId) {
        return runReplayService.replayByRunId(runId)
                .orElseThrow(() -> new IllegalArgumentException("未找到对应 run: " + runId));
    }
}
