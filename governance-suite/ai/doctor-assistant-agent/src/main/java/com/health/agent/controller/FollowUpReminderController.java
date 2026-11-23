package com.health.agent.controller;

import com.health.agent.config.FollowUpReminderProperties;
import com.health.agent.service.HighRiskFollowUpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
public class FollowUpReminderController {
    private final HighRiskFollowUpService reminders;
    private final FollowUpReminderProperties properties;
    private final PatientTaskServiceAuth auth;
    private final AgentAccessGuard accessGuard;

    public FollowUpReminderController(HighRiskFollowUpService reminders, FollowUpReminderProperties properties, AgentAccessGuard accessGuard,
            PatientTaskServiceAuth auth) {
        this.reminders = reminders;
        this.properties = properties;
        this.auth = auth;
        this.accessGuard = accessGuard;
    }

    public record Trigger(@NotBlank @Size(max = 64) String customerId, @NotBlank @Size(max = 128) String requestId,
                          @NotBlank @Size(max = 128) String conversationId, @NotBlank @Size(max = 2000) String patientMessage) {}

    @PostMapping("/internal/patient-tasks/high-risk-follow-up")
    public HighRiskFollowUpService.Receipt open(@Valid @RequestBody Trigger trigger,
            @RequestHeader(value = "X-Follow-Up-Token", defaultValue = "") String token) {
        auth.require(token);
        String doctor = properties.assignedDoctor(trigger.customerId());
        if (doctor == null || doctor.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该患者尚未分配试点责任医生");
        }
        try (var permit = accessGuard.acquirePatientTask(trigger.customerId())) {
            return reminders.open(trigger.customerId(), trigger.requestId(), trigger.conversationId(), trigger.patientMessage(), doctor);
        }
    }

    @GetMapping("/internal/patient-tasks/review-status")
    public HighRiskFollowUpService.ReviewProgress status(@RequestParam String customerId,
            @RequestParam String conversationId,
            @RequestHeader(value = "X-Follow-Up-Token", defaultValue = "") String token) {
        auth.require(token);
        return reminders.latestProgress(customerId, conversationId);
    }

    @GetMapping("/api/patient/follow-up-reminders")
    public List<HighRiskFollowUpService.InboxMessage> inbox(HttpServletRequest request,
            @RequestParam(defaultValue = "9223372036854775807") long beforeId) {
        return reminders.inbox(identity(request, "PATIENT"), beforeId);
    }

    static String identity(HttpServletRequest request, String role) {
        if (request.getUserPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录");
        }
        if (!request.isUserInRole(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色无权操作");
        }
        return request.getUserPrincipal().getName();
    }
}
