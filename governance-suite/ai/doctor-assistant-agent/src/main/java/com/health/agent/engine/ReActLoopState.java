package com.health.agent.engine;

import java.util.List;
import java.util.function.Consumer;

import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentStreamEvent;
import com.health.agent.model.Message;
import com.health.agent.model.Step;

import lombok.Builder;
import lombok.Value;

/**
 * ReAct 引擎循环态快照。
 */
@Value
@Builder(toBuilder = true)
class ReActLoopState {

    String customerId;

    String doctorId;

    List<Step> history;

    List<Message> messages;

    Consumer<AgentStreamEvent> eventSink;

    AgentContext context;
}
