package com.health.agent.service;

import java.util.List;

import com.health.agent.model.ActionDecision;
import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentSession;
import com.health.agent.model.Message;
import com.health.agent.model.PendingTask;
import com.health.agent.model.Step;

import lombok.Builder;
import lombok.Value;

/**
 * 审批通过后的恢复执行状态快照。
 */
@Value
@Builder(toBuilder = true)
public class AgentApprovalResumeState {

    AgentSession session;

    PendingTask pendingTask;

    List<Step> history;

    List<Message> messages;

    AgentContext context;

    int currentStep;

    ActionDecision approvedAction;
}
