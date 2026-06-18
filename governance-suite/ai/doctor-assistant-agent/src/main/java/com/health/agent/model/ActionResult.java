package com.health.agent.model;

import com.lx.governance.agentplatform.model.PlatformToolError;
import lombok.Builder;
import lombok.Data;

/**
 * 行动执行结果
 */
@Data
@Builder
public class ActionResult {
    private boolean success;
    private Object output;
    private String errorMessage;
    private boolean needsApproval;
    private PendingTask task;

    public static ActionResult success(Object output) {
        return ActionResult.builder()
            .success(true)
            .output(output)
            .needsApproval(false)
            .build();
    }

    public static ActionResult pendingApproval(PendingTask task) {
        return ActionResult.builder()
            .success(false)
            .needsApproval(true)
            .task(task)
            .build();
    }

    public static ActionResult error(String message) {
        return error("TOOL_EXECUTION_ERROR", message);
    }

    public static ActionResult error(String code, String message) {
        return ActionResult.builder()
            .success(false)
            .output(PlatformToolError.builder()
                .code(code)
                .message(message)
                .retriable(isRetriable(code))
                .build())
            .errorMessage(message)
            .needsApproval(false)
            .build();
    }

    private static boolean isRetriable(String code) {
        return "TIMEOUT".equals(code)
            || "TOOL_THROTTLED".equals(code)
            || "TOOL_CIRCUIT_OPEN".equals(code);
    }
}
