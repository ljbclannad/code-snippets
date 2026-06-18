package com.lx.governance.agentplatform.runtime;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.lx.governance.agentplatform.model.PlatformContext;
import com.lx.governance.agentplatform.model.PlatformContextBudget;
import com.lx.governance.agentplatform.model.PlatformContextChannel;
import com.lx.governance.agentplatform.model.PlatformContextEvidence;
import com.lx.governance.agentplatform.model.PlatformContextField;
import com.lx.governance.agentplatform.model.PlatformToolOutput;

/**
 * 默认平台上下文投影器。
 */
@Component
public class DefaultPlatformContextProjector implements PlatformContextProjector {

    private static final String DEFAULT_SCHEMA_VERSION = "platform-context/v1";

    @Override
    public PlatformContext project(
            PlatformContext source,
            PlatformContextChannel channel,
            PlatformContextBudget budget) {
        if (source == null) {
            return null;
        }
        PlatformContextBudget safeBudget = budget != null ? budget : PlatformContextBudget.builder().build();
        List<PlatformContextField> projectedFields = projectFields(source.getFields(), safeBudget);
        List<PlatformContextEvidence> projectedEvidences = projectEvidences(source.getEvidences(), safeBudget);
        List<PlatformToolOutput> projectedToolOutputs = projectToolOutputs(source.getToolOutputs(), safeBudget);
        PlatformContext.PlatformContextBuilder builder = PlatformContext.builder()
                .schemaVersion(source.getSchemaVersion() != null ? source.getSchemaVersion() : DEFAULT_SCHEMA_VERSION)
                .channel(channel)
                .contextId(source.getContextId())
                .conversationId(source.getConversationId())
                .customerId(source.getCustomerId())
                .doctorId(source.getDoctorId());
        projectedFields.forEach(builder::field);
        builder.evidences(projectedEvidences);
        builder.toolOutputs(projectedToolOutputs);
        return builder.build();
    }

    private List<PlatformContextField> projectFields(
            List<PlatformContextField> fields,
            PlatformContextBudget budget) {
        List<PlatformContextField> projected = new ArrayList<>();
        if (fields == null || fields.isEmpty()) {
            return projected;
        }
        int maxFields = safeInt(budget.getMaxFields(), fields.size());
        int maxFieldChars = safeInt(budget.getMaxFieldChars(), Integer.MAX_VALUE);
        int maxTotalChars = safeInt(budget.getMaxTotalChars(), Integer.MAX_VALUE);
        int totalChars = 0;
        for (PlatformContextField field : fields) {
            if (field == null || projected.size() >= maxFields) {
                break;
            }
            Object value = truncateValue(field.getValue(), maxFieldChars);
            int valueChars = String.valueOf(value).length();
            if (totalChars + valueChars > maxTotalChars) {
                break;
            }
            projected.add(field.toBuilder().value(value).build());
            totalChars += valueChars;
        }
        return projected;
    }

    private List<PlatformContextEvidence> projectEvidences(
            List<PlatformContextEvidence> evidences,
            PlatformContextBudget budget) {
        List<PlatformContextEvidence> projected = new ArrayList<>();
        if (evidences == null || evidences.isEmpty()) {
            return projected;
        }
        int max = safeInt(budget.getMaxEvidences(), evidences.size());
        for (PlatformContextEvidence evidence : evidences) {
            if (evidence == null || projected.size() >= max) {
                break;
            }
            projected.add(evidence);
        }
        return projected;
    }

    private List<PlatformToolOutput> projectToolOutputs(
            List<PlatformToolOutput> toolOutputs,
            PlatformContextBudget budget) {
        List<PlatformToolOutput> projected = new ArrayList<>();
        if (toolOutputs == null || toolOutputs.isEmpty()) {
            return projected;
        }
        int max = safeInt(budget.getMaxToolOutputs(), toolOutputs.size());
        for (PlatformToolOutput toolOutput : toolOutputs) {
            if (toolOutput == null || projected.size() >= max) {
                break;
            }
            projected.add(toolOutput);
        }
        return projected;
    }

    private Object truncateValue(Object value, int maxChars) {
        if (value == null) {
            return null;
        }
        String serialized = String.valueOf(value);
        if (serialized.length() <= maxChars) {
            return value;
        }
        return serialized.substring(0, Math.max(16, maxChars)) + "...";
    }

    private int safeInt(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
