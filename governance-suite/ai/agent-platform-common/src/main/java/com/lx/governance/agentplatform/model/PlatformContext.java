package com.lx.governance.agentplatform.model;

import java.util.ArrayList;
import java.util.List;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * 平台统一上下文总线载荷。
 */
@Value
@Builder(toBuilder = true)
public class PlatformContext {

    String schemaVersion;

    PlatformContextChannel channel;

    String contextId;

    String conversationId;

    String customerId;

    String doctorId;

    @Singular("field")
    List<PlatformContextField> fields;

    @Builder.Default
    List<PlatformContextEvidence> evidences = new ArrayList<>();

    @Builder.Default
    List<PlatformToolOutput> toolOutputs = new ArrayList<>();
}
