package com.health.agent.service;

import java.util.LinkedHashMap;
import java.util.Map;

import com.alibaba.fastjson2.JSON;
import com.health.agent.model.ActionDecision;
import com.health.agent.model.ActionResult;
import com.health.agent.model.AgentContext;
import com.health.agent.model.ToolEvidence;
import com.health.agent.model.ToolExecutionOutput;
import com.lx.governance.agentplatform.model.PlatformContextEvidence;
import com.lx.governance.agentplatform.model.PlatformContextVisibility;
import com.lx.governance.agentplatform.model.PlatformToolError;
import com.lx.governance.agentplatform.model.PlatformToolOutput;
import org.springframework.stereotype.Service;

/**
 * 统一处理工具执行后的 observation 格式化与上下文回灌。
 */
@Service
public class AgentContextMutationService {

    public String formatObservation(ActionResult result) {
        if (result != null && result.getOutput() instanceof PlatformToolError platformToolError) {
            return JSON.toJSONString(platformToolError);
        }
        if (result != null && result.isSuccess()) {
            return JSON.toJSONString(result.getOutput());
        }
        return "Error: " + (result != null ? result.getErrorMessage() : "unknown");
    }

    public void applyActionResult(
            AgentContext context,
            ActionDecision action,
            ActionResult actionResult,
            String observation) {
        if (context == null || action == null) {
            return;
        }

        Object payload = actionResult != null ? actionResult.getOutput() : observation;
        if (payload instanceof PlatformToolOutput toolOutput) {
            if (toolOutput.getStateUpdates() != null) {
                toolOutput.getStateUpdates().forEach((key, value) ->
                        context.putSlot(
                                key,
                                value,
                                action.getToolName(),
                                toolOutput.getVisibility() != null
                                        ? toolOutput.getVisibility()
                                        : PlatformContextVisibility.AGENT_INTERNAL));
            }
            if (toolOutput.getEvidences() != null) {
                for (PlatformContextEvidence evidence : toolOutput.getEvidences()) {
                    context.addEvidence(
                            action.getToolName(),
                            evidence.getSource(),
                            evidence.getSummary(),
                            evidence.getPayload(),
                            evidence.getVisibility() != null
                                    ? evidence.getVisibility()
                                    : PlatformContextVisibility.AGENT_INTERNAL);
                }
            }
        } else if (payload instanceof ToolExecutionOutput toolOutput) {
            if (toolOutput.getStateUpdates() != null) {
                toolOutput.getStateUpdates().forEach((key, value) -> context.putSlot(key, value, action.getToolName()));
            }
            if (toolOutput.getEvidence() != null) {
                for (ToolEvidence evidence : toolOutput.getEvidence()) {
                    context.addEvidence(
                            action.getToolName(),
                            evidence.getSource(),
                            evidence.getSummary(),
                            evidence.getPayload());
                }
            }
        } else {
            String slotKey = resolveContextSlot(action.getToolName());
            context.putSlot(slotKey, payload, action.getToolName());
            context.addEvidence(action.getToolName(), "TOOL_OUTPUT", observation, payload);
        }
        updateLastToolResult(context, action, observation);
    }

    private String resolveContextSlot(String toolName) {
        return switch (toolName) {
            case "get_patient_profile" -> "patient_profile";
            case "get_weight_history" -> "weight_history";
            case "get_chat_history" -> "chat_history";
            case "get_prescription_history" -> "prescription_history";
            case "get_follow_up_tasks", "create_follow_up_task" -> "follow_up_tasks";
            case "analyze_weight_trend" -> "weight_trend_analysis";
            case "detect_weight_plateau" -> "plateau_assessment";
            case "analyze_adherence_signals" -> "adherence_analysis";
            case "get_health_knowledge" -> "health_knowledge";
            default -> toolName;
        };
    }

    private void updateLastToolResult(AgentContext context, ActionDecision action, String observation) {
        Map<String, Object> lastToolResult = new LinkedHashMap<>();
        lastToolResult.put("toolName", action.getToolName());
        lastToolResult.put("input", action.getInput());
        lastToolResult.put("observation", observation);
        context.putSlot("last_tool_result", lastToolResult, action.getToolName());
    }
}
