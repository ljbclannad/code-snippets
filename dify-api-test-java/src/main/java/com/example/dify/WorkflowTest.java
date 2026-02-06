package com.example.dify;

import java.util.HashMap;
import java.util.Map;

import io.github.imfangs.dify.client.DifyClientFactory;
import io.github.imfangs.dify.client.DifyWorkflowClient;
import io.github.imfangs.dify.client.enums.ResponseMode;
import io.github.imfangs.dify.client.model.workflow.WorkflowRunRequest;
import io.github.imfangs.dify.client.model.workflow.WorkflowRunResponse;

public class WorkflowTest {
    public static void main(String[] args) throws Exception {
        String apiKey = DifyConfig.apiKey();
        String baseUrl = DifyConfig.baseUrl();
        DifyWorkflowClient workflowClient = DifyClientFactory.createWorkflowClient(baseUrl,
                apiKey);
        WorkflowRunRequest request = new WorkflowRunRequest();
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("user_id", String.valueOf(System.currentTimeMillis()));
        inputs.put("report_id", "");
        request.setInputs(inputs);
        request.setResponseMode(ResponseMode.BLOCKING);
        request.setUser(DifyConfig.user());
        WorkflowRunResponse response = workflowClient.runWorkflowById("4909168d-ea37-49a9-ad42-e97a4be92b2d", request);
        System.out.println(response);
    }

}
