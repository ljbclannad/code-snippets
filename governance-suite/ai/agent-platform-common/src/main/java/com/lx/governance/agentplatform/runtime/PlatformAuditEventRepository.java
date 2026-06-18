package com.lx.governance.agentplatform.runtime;

import java.util.List;

import com.lx.governance.agentplatform.model.PlatformAuditEvent;

/**
 * 平台审计事件仓储抽象。
 */
public interface PlatformAuditEventRepository {

    PlatformAuditEvent save(PlatformAuditEvent event);

    List<PlatformAuditEvent> findAuditEventsByRunId(String runId);
}
