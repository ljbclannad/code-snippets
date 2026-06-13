package com.lx.governance.aichat.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TaskTriggerRouter 的判定结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskTriggerDecision {

    private TaskTriggerRoute route;
    private TaskType taskType;

    @Builder.Default
    private List<TaskTriggerReason> reasons = new ArrayList<>();

    private String summary;

    public boolean shouldUpgradeToAgent() {
        return route == TaskTriggerRoute.DOCTOR_ASSISTANT_AGENT;
    }
}
