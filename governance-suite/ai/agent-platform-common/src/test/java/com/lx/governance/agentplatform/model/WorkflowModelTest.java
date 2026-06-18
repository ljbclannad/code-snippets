package com.lx.governance.agentplatform.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorkflowModelTest {

    @Test
    void shouldBuildWorkflowDefinitionWithPoliciesAndNodes() {
        WorkflowNode routeNode = WorkflowNode.builder()
                .nodeId("route-1")
                .nodeName("task-router")
                .nodeType(WorkflowNodeType.ROUTE)
                .configEntry("selector", "taskType")
                .build();

        WorkflowNode approvalNode = WorkflowNode.builder()
                .nodeId("approval-1")
                .nodeName("doctor-approval")
                .nodeType(WorkflowNodeType.WAIT_APPROVAL)
                .approvalPolicy(WorkflowApprovalPolicy.builder()
                        .approvalScope("doctor")
                        .approvalKey("risk.high")
                        .approvalReason("high risk tool call")
                        .approvedTargetNodeId("finalize-1")
                        .rejectedTargetNodeId("fallback-1")
                        .approvalTimeoutMs(300000L)
                        .build())
                .manualInterventionPolicy(WorkflowManualInterventionPolicy.builder()
                        .interventionType(WorkflowManualInterventionType.APPROVAL)
                        .queueKey("doctor-review")
                        .assigneeType("doctor")
                        .timeoutMs(300000L)
                        .resumeNodeId("finalize-1")
                        .rejectNodeId("fallback-1")
                        .build())
                .build();

        WorkflowDefinition definition = WorkflowDefinition.builder()
                .workflowId("patient-follow-up")
                .workflowName("patient-follow-up-workflow")
                .version("v1")
                .description("route, approval and finalize for patient task")
                .startNodeId("route-1")
                .inputSchemaEntry("taskType", "string")
                .outputSchemaEntry("patientSafeReply", "string")
                .timeoutPolicy(WorkflowTimeoutPolicy.builder()
                        .timeoutMs(600000L)
                        .timeoutTargetNodeId("fallback-1")
                        .timeoutRunStatus(RunStatus.DEGRADED)
                        .build())
                .retryPolicy(WorkflowRetryPolicy.builder()
                        .maxAttempts(2)
                        .initialBackoffMs(500L)
                        .backoffMultiplier(2.0d)
                        .retryableError("TimeoutException")
                        .build())
                .fallbackPolicy(WorkflowFallbackPolicy.builder()
                        .fallbackNodeId("fallback-1")
                        .fallbackAgentId("doctor.care-planning")
                        .fallbackReason("route failed")
                        .fallbackRunStatus(RunStatus.DEGRADED)
                        .build())
                .manualInterventionPolicy(WorkflowManualInterventionPolicy.builder()
                        .interventionType(WorkflowManualInterventionType.REVIEW)
                        .queueKey("agent-platform-review")
                        .assigneeType("ops")
                        .timeoutMs(900000L)
                        .resumeNodeId("route-1")
                        .rejectNodeId("fallback-1")
                        .build())
                .node(routeNode)
                .node(approvalNode)
                .edge(WorkflowEdge.builder()
                        .edgeId("edge-1")
                        .sourceNodeId("route-1")
                        .targetNodeId("approval-1")
                        .transitionType(WorkflowTransitionType.ON_SUCCESS)
                        .build())
                .build();

        assertEquals("patient-follow-up", definition.getWorkflowId());
        assertEquals(2, definition.getNodes().size());
        assertEquals(1, definition.getEdges().size());
        assertEquals(WorkflowNodeType.WAIT_APPROVAL, definition.getNodes().get(1).getNodeType());
        assertEquals("doctor", definition.getNodes().get(1).getApprovalPolicy().getApprovalScope());
        assertEquals(WorkflowManualInterventionType.REVIEW,
                definition.getManualInterventionPolicy().getInterventionType());
        assertEquals(RunStatus.DEGRADED, definition.getFallbackPolicy().getFallbackRunStatus());
    }
}
