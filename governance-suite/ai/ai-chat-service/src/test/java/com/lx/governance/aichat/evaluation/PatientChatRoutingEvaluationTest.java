package com.lx.governance.aichat.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.TaskTriggerContext;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.TaskTriggerReason;
import com.lx.governance.aichat.dto.TaskTriggerRoute;
import com.lx.governance.aichat.dto.TaskType;
import com.lx.governance.aichat.service.TaskTriggerRouter;

class PatientChatRoutingEvaluationTest {

    private static final String DATASET = "/evaluation/patient-chat-v2.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TaskTriggerRouter router = new TaskTriggerRouter();

    @TestFactory
    List<DynamicTest> shouldMatchThePatientChatRoutingBaseline() throws IOException {
        List<PatientChatCase> cases = loadCases();
        assertTrue(cases.size() == 14, "v2 固定路由回归集应包含 14 条合成样本");
        assertEquals(cases.size(), cases.stream().map(PatientChatCase::id).distinct().count(),
            "固定样本 ID 不得重复");
        assertTrue(cases.stream().allMatch(testCase -> testCase.requiredAnswerSignals() != null
                && testCase.forbiddenAnswerSignals() != null), "每条样本都必须给出回答评测规则");
        assertTrue(cases.stream()
                .filter(testCase -> !"NONE".equals(testCase.expectedTaskType()))
                .allMatch(testCase -> !testCase.requiredAnswerSignals().isEmpty()
                        && !testCase.forbiddenAnswerSignals().isEmpty()),
            "Agent 路由样本必须同时给出必含与禁含回答信号");

        return cases.stream()
            .map(testCase -> DynamicTest.dynamicTest(testCase.id() + ": " + testCase.description(),
                () -> assertDecision(testCase)))
            .toList();
    }

    private void assertDecision(PatientChatCase testCase) {
        SessionContext sessionContext = SessionContext.builder()
            .complexity(testCase.complexity())
            .messageCount(testCase.messageCount())
            .userIntent(testCase.userIntent())
            .pendingQuestions(testCase.pendingQuestions())
            .build();

        TaskTriggerDecision actual = router.route(TaskTriggerContext.builder()
            .message(testCase.message())
            .sessionContext(sessionContext)
            .build());

        assertEquals(TaskTriggerRoute.valueOf(testCase.expectedRoute()), actual.getRoute());
        assertEquals(TaskType.valueOf(testCase.expectedTaskType()), actual.getTaskType());
        assertTrue(actual.getReasons().contains(TaskTriggerReason.valueOf(testCase.expectedReason())),
            () -> "期望原因 " + testCase.expectedReason() + "，实际为 " + actual.getReasons());
    }

    private List<PatientChatCase> loadCases() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(DATASET)) {
            if (input == null) {
                throw new IllegalStateException("找不到评测集: " + DATASET);
            }
            return objectMapper.readValue(input, new TypeReference<>() { });
        }
    }

    record PatientChatCase(
        String id,
        String description,
        String message,
        String complexity,
        int messageCount,
        String userIntent,
        List<String> pendingQuestions,
        String expectedRoute,
        String expectedTaskType,
        String expectedReason,
        List<String> requiredAnswerSignals,
        List<String> forbiddenAnswerSignals) {
    }
}
