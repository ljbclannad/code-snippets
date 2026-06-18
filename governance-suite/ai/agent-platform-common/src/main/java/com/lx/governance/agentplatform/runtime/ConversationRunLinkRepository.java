package com.lx.governance.agentplatform.runtime;

import java.util.Optional;

import com.lx.governance.agentplatform.model.ConversationRunLink;

/**
 * 对话入口与平台 run 关系仓储。
 */
public interface ConversationRunLinkRepository {

    ConversationRunLink save(ConversationRunLink link);

    Optional<ConversationRunLink> findLatestByConversationId(String conversationId);

    Optional<ConversationRunLink> findByRunId(String runId);
}
