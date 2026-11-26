package com.health.agent.tool;

import com.health.agent.model.ToolEvidence;
import com.health.agent.model.ToolExecutionOutput;
import com.lx.governance.agentplatform.model.PlatformContextEvidence;
import com.lx.governance.agentplatform.model.PlatformContextVisibility;
import com.lx.governance.agentplatform.model.PlatformToolOutput;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 doctor 场景工具输出适配为平台统一协议。
 */
@Component
public class PlatformToolOutputAdapter {

    public PlatformToolOutput toPlatformOutput(
            String toolName,
            String defaultSummary,
            Object rawOutput,
            String fallbackSlotKey) {
        if (rawOutput instanceof PlatformToolOutput platformToolOutput) {
            return normalizePlatformOutput(toolName, defaultSummary, platformToolOutput, fallbackSlotKey);
        }
        if (rawOutput instanceof ToolExecutionOutput toolExecutionOutput) {
            return fromLegacyToolOutput(toolName, defaultSummary, toolExecutionOutput, fallbackSlotKey);
        }
        return PlatformToolOutput.builder()
                .toolName(toolName)
                .summary(defaultSummary)
                .visibility(PlatformContextVisibility.AGENT_INTERNAL)
                .data(rawOutput)
                .stateUpdates(buildFallbackStateUpdates(fallbackSlotKey, rawOutput))
                .evidence(PlatformContextEvidence.builder()
                        .evidenceKey(toolName)
                        .source("TOOL_OUTPUT")
                        .summary(defaultSummary)
                        .visibility(PlatformContextVisibility.AGENT_INTERNAL)
                        .payload(rawOutput)
                        .build())
                .build();
    }

    private PlatformToolOutput normalizePlatformOutput(
            String toolName,
            String defaultSummary,
            PlatformToolOutput output,
            String fallbackSlotKey) {
        PlatformToolOutput.PlatformToolOutputBuilder builder = output.toBuilder()
                .toolName(output.getToolName() != null ? output.getToolName() : toolName)
                .summary(output.getSummary() != null ? output.getSummary() : defaultSummary)
                .visibility(output.getVisibility() != null ? output.getVisibility() : PlatformContextVisibility.AGENT_INTERNAL);
        Map<String, Object> stateUpdates = output.getStateUpdates() == null || output.getStateUpdates().isEmpty()
                ? buildFallbackStateUpdates(fallbackSlotKey, output.getData())
                : new LinkedHashMap<>(output.getStateUpdates());
        builder.stateUpdates(stateUpdates);
        if (output.getEvidences() == null || output.getEvidences().isEmpty()) {
            builder.evidences(List.of(
                    PlatformContextEvidence.builder()
                            .evidenceKey(toolName)
                            .source("TOOL_OUTPUT")
                            .summary(builder.build().getSummary())
                            .visibility(PlatformContextVisibility.AGENT_INTERNAL)
                            .payload(output.getData())
                            .build()));
        }
        return builder.build();
    }

    private PlatformToolOutput fromLegacyToolOutput(
            String toolName,
            String defaultSummary,
            ToolExecutionOutput output,
            String fallbackSlotKey) {
        PlatformToolOutput.PlatformToolOutputBuilder builder = PlatformToolOutput.builder()
                .toolName(toolName)
                .summary(output.getSummary() != null ? output.getSummary() : defaultSummary)
                .visibility(PlatformContextVisibility.AGENT_INTERNAL)
                .data(output.getData())
                .stateUpdates(output.getStateUpdates() == null || output.getStateUpdates().isEmpty()
                        ? buildFallbackStateUpdates(fallbackSlotKey, output.getData())
                        : new LinkedHashMap<>(output.getStateUpdates()));

        if (output.getEvidence() != null) {
            for (ToolEvidence evidence : output.getEvidence()) {
                if (evidence == null) {
                    continue;
                }
                builder.evidence(PlatformContextEvidence.builder()
                        .evidenceKey(toolName)
                        .source(evidence.getSource())
                        .summary(evidence.getSummary())
                        .visibility(PlatformContextVisibility.AGENT_INTERNAL)
                        .payload(evidence.getPayload())
                        .build());
            }
        }
        return builder.build();
    }

    private Map<String, Object> buildFallbackStateUpdates(String fallbackSlotKey, Object rawOutput) {
        Map<String, Object> stateUpdates = new LinkedHashMap<>();
        stateUpdates.put(fallbackSlotKey, rawOutput);
        return stateUpdates;
    }
}
