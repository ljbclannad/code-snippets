package com.lx.governance.agentplatform.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.lx.governance.agentplatform.model.ApprovalTask;
import com.lx.governance.agentplatform.model.ArtifactRecord;
import com.lx.governance.agentplatform.model.ConversationRunLink;
import com.lx.governance.agentplatform.model.PlatformAuditEvent;
import com.lx.governance.agentplatform.model.Run;
import com.lx.governance.agentplatform.model.RunEvent;
import com.lx.governance.agentplatform.model.RunPersistenceSnapshot;

/**
 * 学习/本地验证用的内存版平台 run 仓储。
 */
public class InMemoryRunPersistenceRepository implements RunPersistenceRepository,
        RunRepository,
        RunEventRepository,
        ArtifactRepository,
        ConversationRunLinkRepository,
        PlatformAuditEventRepository,
        ApprovalRepository<ApprovalTask> {

    private final Map<String, RunPersistenceSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final Map<String, List<RunEvent>> events = new ConcurrentHashMap<>();
    private final Map<String, List<ArtifactRecord>> artifacts = new ConcurrentHashMap<>();
    private final Map<String, ConversationRunLink> linksByRunId = new ConcurrentHashMap<>();
    private final Map<String, List<ConversationRunLink>> linksByConversationId = new ConcurrentHashMap<>();
    private final Map<String, List<PlatformAuditEvent>> auditEvents = new ConcurrentHashMap<>();
    private final Map<String, ApprovalTask> approvalTasks = new ConcurrentHashMap<>();

    @Override
    public RunPersistenceSnapshot save(RunPersistenceSnapshot snapshot) {
        if (snapshot == null || snapshot.getRun() == null || snapshot.getRun().getRunId() == null) {
            throw new IllegalArgumentException("snapshot.run.runId must not be null");
        }
        snapshots.put(snapshot.getRun().getRunId(), snapshot);
        runs.put(snapshot.getRun().getRunId(), snapshot.getRun());
        if (snapshot.getRun().getEvents() != null) {
            events.put(snapshot.getRun().getRunId(), new ArrayList<>(snapshot.getRun().getEvents()));
        }
        if (snapshot.getArtifacts() != null) {
            artifacts.put(snapshot.getRun().getRunId(), new ArrayList<>(snapshot.getArtifacts()));
        }
        if (snapshot.getApprovalTasks() != null) {
            for (ApprovalTask approvalTask : snapshot.getApprovalTasks()) {
                if (approvalTask != null && approvalTask.getApprovalTaskId() != null) {
                    approvalTasks.put(approvalTask.getApprovalTaskId(), approvalTask);
                }
            }
        }
        return snapshot;
    }

    @Override
    public Optional<RunPersistenceSnapshot> findSnapshotByRunId(String runId) {
        return Optional.ofNullable(snapshots.get(runId));
    }

    @Override
    public Run save(Run run) {
        runs.put(run.getRunId(), run);
        RunPersistenceSnapshot current = snapshots.get(run.getRunId());
        if (current != null) {
            snapshots.put(run.getRunId(), current.toBuilder().run(run).build());
        }
        return run;
    }

    @Override
    public Optional<Run> findRunById(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public RunEvent save(RunEvent runEvent) {
        events.computeIfAbsent(runEvent.getRunId(), key -> new ArrayList<>()).add(runEvent);
        return runEvent;
    }

    @Override
    public List<RunEvent> findEventsByRunId(String runId) {
        return new ArrayList<>(events.getOrDefault(runId, List.of()));
    }

    @Override
    public ArtifactRecord save(ArtifactRecord artifactRecord) {
        artifacts.computeIfAbsent(artifactRecord.getRunId(), key -> new ArrayList<>()).add(artifactRecord);
        return artifactRecord;
    }

    @Override
    public List<ArtifactRecord> findArtifactsByRunId(String runId) {
        return new ArrayList<>(artifacts.getOrDefault(runId, List.of()));
    }

    @Override
    public ConversationRunLink save(ConversationRunLink link) {
        linksByRunId.put(link.getRunId(), link);
        linksByConversationId.computeIfAbsent(link.getConversationId(), key -> new ArrayList<>()).add(link);
        linksByConversationId.get(link.getConversationId()).sort(
                Comparator.comparing(ConversationRunLink::getLinkedAt, Comparator.nullsLast(Long::compareTo)));
        return link;
    }

    @Override
    public Optional<ConversationRunLink> findLatestByConversationId(String conversationId) {
        List<ConversationRunLink> links = linksByConversationId.getOrDefault(conversationId, List.of());
        if (links.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(links.get(links.size() - 1));
    }

    @Override
    public Optional<ConversationRunLink> findByRunId(String runId) {
        return Optional.ofNullable(linksByRunId.get(runId));
    }

    @Override
    public ApprovalTask save(ApprovalTask approvalRecord) {
        approvalTasks.put(approvalRecord.getApprovalTaskId(), approvalRecord);
        return approvalRecord;
    }

    @Override
    public ApprovalTask findById(String approvalTaskId) {
        return approvalTasks.get(approvalTaskId);
    }

    @Override
    public PlatformAuditEvent save(PlatformAuditEvent event) {
        auditEvents.computeIfAbsent(event.getRunId(), key -> new ArrayList<>()).add(event);
        return event;
    }

    @Override
    public List<PlatformAuditEvent> findAuditEventsByRunId(String runId) {
        return new ArrayList<>(auditEvents.getOrDefault(runId, List.of()));
    }
}
