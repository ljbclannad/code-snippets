package com.lx.governance.aichat.service;

import com.lx.governance.aichat.dto.DoctorAssistantTaskRequest;
import com.lx.governance.aichat.dto.DoctorAssistantTaskResponse;

/**
 * ai-chat-service 调用 doctor-assistant-agent 的网关抽象。
 */
public interface DoctorAssistantAgentGateway {

    DoctorAssistantTaskResponse analyzePatientTask(DoctorAssistantTaskRequest request);
}
