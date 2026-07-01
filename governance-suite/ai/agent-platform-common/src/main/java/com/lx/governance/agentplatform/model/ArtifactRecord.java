package com.lx.governance.agentplatform.model;

import java.util.Map;

import lombok.Builder;
import lombok.Value;

/**
 * 平台运行产物记录，对应未来的 agent_artifact 持久化对象。
 */
@Value
@Builder(toBuilder = true)
public class ArtifactRecord {

    String artifactId;

    String runId;

    String stepId;

    String artifactType;

    String artifactName;

    ArtifactScope scope;

    String contentType;

    String storageUri;

    String inlineContent;

    Map<String, Object> metadata;

    Long createdAt;
}
