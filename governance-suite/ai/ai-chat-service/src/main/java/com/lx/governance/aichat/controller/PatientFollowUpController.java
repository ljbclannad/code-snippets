package com.lx.governance.aichat.controller;

import com.lx.governance.aichat.dto.PatientFollowUpStatus;
import com.lx.governance.aichat.service.ChatConversationAccess;
import com.lx.governance.aichat.service.DoctorAssistantAgentEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/patient")
public class PatientFollowUpController {
    private final ChatConversationAccess access;
    private final RestClient client;
    private final DoctorAssistantAgentEndpoint endpoint;
    private final String serviceToken;

    public PatientFollowUpController(ChatConversationAccess access,
            @Qualifier("doctorAssistantAgentRestClient") RestClient client, DoctorAssistantAgentEndpoint endpoint,
            @Value("${ai-chat.follow-up-reminder.service-token:}") String serviceToken) {
        this.access = access;
        this.client = client;
        this.endpoint = endpoint;
        this.serviceToken = serviceToken;
    }

    public record Reminder(String receiptId, String message, LocalDateTime deliveredAt) {}

    @GetMapping("/session")
    public ResponseEntity<Map<String, String>> session(HttpServletRequest caller) {
        if (caller.getUserPrincipal() == null) {
            return response(Map.of());
        }
        return response(Map.of("customerId", ChatConversationAccess.requirePatient(caller, null)));
    }

    @GetMapping("/follow-up-progress")
    public ResponseEntity<PatientFollowUpStatus> progress(HttpServletRequest caller, @RequestParam String conversationId) {
        String patient = ChatConversationAccess.requirePatient(caller, null);
        access.requireOwnedConversation(conversationId, patient);
        return response(read(() -> request("/internal/patient-tasks/follow-up-status", patient, "conversationId", conversationId)
                .body(PatientFollowUpStatus.class)));
    }

    @GetMapping("/follow-up-reminders")
    public ResponseEntity<List<Reminder>> inbox(HttpServletRequest caller,
            @RequestParam(defaultValue = "9223372036854775807") long beforeId) {
        String patient = ChatConversationAccess.requirePatient(caller, null);
        return response(read(() -> request("/internal/patient-tasks/follow-up-inbox", patient, "beforeId", beforeId)
                .body(new ParameterizedTypeReference<List<Reminder>>() {})));
    }

    private RestClient.ResponseSpec request(String path, String patient, String param, Object value) {
        var uri = UriComponentsBuilder.fromUri(endpoint.resolve(path))
                .queryParam("customerId", "{patient}").queryParam(param, "{value}").encode().buildAndExpand(patient, value).toUri();
        return client.get().uri(uri).header("X-Follow-Up-Token", serviceToken).retrieve();
    }

    private <T> T read(Supplier<T> operation) {
        if (!serviceToken.isBlank()) {
            try {
                T result = operation.get();
                if (result != null) {
                    return result;
                }
            } catch (RestClientException ignored) {
                // 服务间响应的错误细节留在服务端。
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "随访服务暂不可用");
    }

    private static <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
