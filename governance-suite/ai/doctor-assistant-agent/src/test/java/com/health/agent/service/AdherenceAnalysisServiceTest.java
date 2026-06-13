package com.health.agent.service;

import com.health.agent.model.ChatMessage;
import com.health.agent.model.FollowUpTask;
import com.health.agent.model.WeightRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdherenceAnalysisServiceTest {

    private AdherenceAnalysisService adherenceAnalysisService;

    @BeforeEach
    void setUp() {
        adherenceAnalysisService = new AdherenceAnalysisService();
        ReflectionTestUtils.setField(adherenceAnalysisService, "analysisWindowDays", 14);
        ReflectionTestUtils.setField(adherenceAnalysisService, "minWeightRecords", 3);
    }

    @Test
    void shouldIdentifyLowDietAndExerciseAdherenceFromNegativeSignals() {
        List<WeightRecord> weightRecords = List.of(
            weightRecord("C001", 80.0, LocalDate.now().minusDays(13)),
            weightRecord("C001", 80.1, LocalDate.now().minusDays(7))
        );
        List<ChatMessage> chatMessages = List.of(
            chat("C001", "USER", "最近总吃夜宵，没控制住，运动也停了"),
            chat("C001", "USER", "这两周基本没怎么运动")
        );
        List<FollowUpTask> tasks = List.of(
            followUp("C001", "记录饮食", LocalDate.now().minusDays(2), FollowUpTask.TaskStatusEnum.PENDING)
        );

        Map<String, Object> result = adherenceAnalysisService.analyze(
            "C001",
            weightRecords,
            chatMessages,
            tasks
        );

        assertEquals("LOW", result.get("dietAdherence"));
        assertEquals("LOW", result.get("exerciseAdherence"));
        assertEquals("LOW", result.get("overallAdherence"));
        assertEquals("HIGH", result.get("riskLevel"));
        assertTrue(((List<?>) result.get("evidence")).size() >= 2);
    }

    @Test
    void shouldIdentifyGoodAdherenceFromConsistentRecordsAndPositiveSignals() {
        List<WeightRecord> weightRecords = List.of(
            weightRecord("C002", 82.0, LocalDate.now().minusDays(13)),
            weightRecord("C002", 81.4, LocalDate.now().minusDays(9)),
            weightRecord("C002", 80.9, LocalDate.now().minusDays(5)),
            weightRecord("C002", 80.5, LocalDate.now().minusDays(1))
        );
        List<ChatMessage> chatMessages = List.of(
            chat("C002", "USER", "这周一直按饮食计划执行，每天快走40分钟"),
            chat("C002", "USER", "晚餐控制得不错，运动也坚持住了")
        );
        List<FollowUpTask> tasks = List.of(
            followUp("C002", "继续记录饮食", LocalDate.now().plusDays(2), FollowUpTask.TaskStatusEnum.COMPLETED)
        );

        Map<String, Object> result = adherenceAnalysisService.analyze(
            "C002",
            weightRecords,
            chatMessages,
            tasks
        );

        assertEquals("HIGH", result.get("dietAdherence"));
        assertEquals("HIGH", result.get("exerciseAdherence"));
        assertEquals("HIGH", result.get("overallAdherence"));
        assertEquals("LOW", result.get("riskLevel"));
    }

    private WeightRecord weightRecord(String customerId, double weight, LocalDate date) {
        return WeightRecord.builder()
            .customerId(customerId)
            .weight(weight)
            .recordDate(date)
            .createdAt(date.atStartOfDay())
            .build();
    }

    private ChatMessage chat(String customerId, String sender, String message) {
        return ChatMessage.builder()
            .customerId(customerId)
            .sender(sender)
            .message(message)
            .createdAt(LocalDateTime.now())
            .build();
    }

    private FollowUpTask followUp(String customerId, String taskDescription, LocalDate dueDate, FollowUpTask.TaskStatusEnum status) {
        return FollowUpTask.builder()
            .customerId(customerId)
            .taskDescription(taskDescription)
            .dueDate(dueDate)
            .status(status)
            .createdAt(LocalDateTime.now())
            .build();
    }
}
