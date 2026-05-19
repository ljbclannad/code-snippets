package com.health.agent.controller;

import com.health.agent.engine.ReActAgentEngine;
import com.health.agent.model.AgentResult;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * Agent API 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    @Autowired
    private ReActAgentEngine agentEngine;

    /**
     * 执行 Agent 任务
     */
    @PostMapping("/execute")
    public AgentResult execute(@RequestBody AgentExecuteRequest request) {
        log.info("收到Agent执行请求: goal={}, customerId={}",
            request.getGoal(), request.getCustomerId());

        return agentEngine.execute(
            request.getGoal(),
            request.getCustomerId(),
            request.getDoctorId()
        );
    }

    /**
     * 恢复 Agent 执行（人工确认后）
     */
    @PostMapping("/resume")
    public AgentResult resume(@RequestBody AgentResumeRequest request) {
        log.info("恢复Agent执行: sessionId={}, taskId={}",
            request.getSessionId(), request.getApprovedTaskId());

        return agentEngine.resume(
            request.getSessionId(),
            request.getApprovedTaskId()
        );
    }

    /**
     * Agent 执行请求
     */
    @Data
    public static class AgentExecuteRequest {
        private String goal;
        private String customerId;
        private String doctorId;
    }

    /**
     * Agent 恢复请求
     */
    @Data
    public static class AgentResumeRequest {
        private String sessionId;
        private Long approvedTaskId;
    }
}
