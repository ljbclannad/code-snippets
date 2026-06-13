package com.health.agent.model;

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
        return ActionResult.builder()
            .success(false)
            .errorMessage(message)
            .needsApproval(false)
            .build();
    }
}
