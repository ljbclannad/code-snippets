package com.lx.governance.aichat.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatResponse;
import com.lx.governance.aichat.dto.DoctorAssistantTaskResponse;
import com.lx.governance.aichat.dto.DoctorAssistantTaskStatus;
import com.lx.governance.aichat.dto.StreamEvent;
import com.lx.governance.aichat.stream.StreamResponseAccumulator;
import com.lx.governance.agentplatform.model.PlatformTraceContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatEvaluationCollectorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void disabledAndUnboundCallsNeverPublishOrAllocateCapture() {
        EvaluationCaptureProperties properties = properties();
        RecordingTransport transport = new RecordingTransport(properties, mapper);
        properties.setEnabled(false);
        ChatEvaluationCollector collector = collector(properties, transport, Runnable::run);
        ChatRequest request = request();
        collector.begin(request);
        collector.complete(request, ChatResponse.success("你好", "conversation-test"), null);
        assertNull(request.getEvaluationCaptureContext());
        assertTrue(transport.observations.isEmpty());
        properties.setEnabled(true);
        request.setEvaluation(null);
        collector.begin(request);
        assertNull(request.getEvaluationCaptureContext());
    }

    @Test
    void snapshotsReplyBeforeAsyncDeliveryAndRedactsIdentifiers() throws Exception {
        EvaluationCaptureProperties properties = properties();
        RecordingTransport transport = new RecordingTransport(properties, mapper);
        List<Runnable> queue = new ArrayList<>();
        ChatEvaluationCollector collector = collector(properties, transport, queue::add);
        ChatRequest request = request();
        collector.begin(request);
        ChatResponse response = ChatResponse.success("联系13812345678或test@example.com，patient-001", "conversation-test");
        collector.complete(request, response, null);
        collector.complete(request, response, null);
        response.setMessage("后续改变");
        assertEquals(1, queue.size());
        assertTrue(transport.observations.isEmpty());
        queue.get(0).run();
        JsonNode observation = transport.observations.get(0);
        String stored = observation.toString();
        assertFalse(stored.contains("13812345678"));
        assertFalse(stored.contains("test@example.com"));
        assertFalse(stored.contains("patient-001"));
        assertFalse(stored.contains("后续改变"));
        assertEquals(64, observation.at("/output/capture/conversationIdHash").asText().length());
        assertEquals("测试问题", observation.at("/input/message").asText());
        assertTrue(observation.path("totalTokens").isNull());
        assertFalse(mapper.writeValueAsString(request).contains("evaluationCaptureContext"));
        ChatRequest decoded = mapper.readValue("{\"message\":\"x\",\"evaluationCaptureContext\":{\"runId\":\"injected\"}}", ChatRequest.class);
        assertNull(decoded.getEvaluationCaptureContext());
    }

    @Test
    void capturesCorrelatedReplayWithoutRawInputsAndRetriesIdenticalObservation() throws Exception {
        EvaluationCaptureProperties properties = properties();
        RecordingTransport transport = new RecordingTransport(properties, mapper);
        transport.failures = 1;
        transport.replay = mapper.readTree("""
                {"runId":"run-1","run":{"status":"WAITING_APPROVAL","input":{"private":"secret"}},
                 "toolCalls":[{"toolName":"sendReminder","status":"PENDING","resultSummary":"未执行"}],
                 "approvalTasks":[{"status":"PENDING","assigneeId":"doctor-real"}]}
                """);
        ChatEvaluationCollector collector = collector(properties, transport, Runnable::run);
        ChatRequest request = request();
        collector.begin(request);
        collector.agent(request, DoctorAssistantTaskResponse.builder().traceContext(
                PlatformTraceContext.builder().runId("run-1").build()).needsHumanReview(true).build());
        collector.complete(request, ChatResponse.success("等待审核", "conversation-test"), null);
        assertEquals("run-1", transport.requestedRunId);
        assertEquals(2, transport.attempts.size());
        assertEquals(transport.attempts.get(0), transport.attempts.get(1));
        JsonNode result = transport.observations.get(0);
        assertEquals("AVAILABLE", result.at("/output/capture/replayStatus").asText());
        assertEquals("PENDING", result.at("/output/replay/approvalTasks/0/status").asText());
        assertFalse(result.toString().contains("doctor-real"));
        assertFalse(result.toString().contains("secret"));
        assertTrue(result.path("error").isNull());
    }

    @Test
    void queueFailureCannotEscapeAndMissingReplayCannotPassSilently() {
        EvaluationCaptureProperties properties = properties();
        RecordingTransport transport = new RecordingTransport(properties, mapper);
        ChatEvaluationCollector rejected = collector(properties, transport, task -> { throw new RejectedExecutionException(); });
        ChatRequest request = request();
        rejected.begin(request);
        assertDoesNotThrow(() -> rejected.complete(request, ChatResponse.success("回答", "conversation-test"), null));
        assertTrue(transport.observations.isEmpty());
        ChatEvaluationCollector collector = collector(properties, transport, Runnable::run);
        collector.begin(request);
        collector.agent(request, DoctorAssistantTaskResponse.builder().traceContext(
                PlatformTraceContext.builder().runId("missing-run").build()).build());
        collector.complete(request, ChatResponse.success("回答", "conversation-test"), null);
        assertEquals("CAPTURE_REPLAY_FAILED", transport.observations.get(0).path("error").asText());
    }

    @Test
    void streamCancellationAndIncompleteResultsAreErrorsEvenWithPartialText() {
        EvaluationCaptureProperties properties = properties();
        RecordingTransport transport = new RecordingTransport(properties, mapper);
        ChatEvaluationCollector collector = collector(properties, transport, Runnable::run);
        ChatRequest request = request();
        StreamResponseAccumulator accumulator = new StreamResponseAccumulator();
        accumulator.accumulate(StreamEvent.content("部分内容"));
        collector.begin(request);
        collector.completeStream(request, accumulator, "CANCEL");
        assertEquals("STREAM_CANCELLED", transport.observations.get(0).path("error").asText());
        collector.begin(request);
        collector.completeStream(request, accumulator, "ON_COMPLETE");
        assertEquals("STREAM_INCOMPLETE", transport.observations.get(1).path("error").asText());
        collector.begin(request);
        accumulator.accumulate(StreamEvent.done("conversation-test", StreamEvent.TokenUsage.builder().totalTokens(12).build()));
        collector.completeStream(request, accumulator, "ON_COMPLETE");
        assertTrue(transport.observations.get(2).path("error").isNull());
        assertEquals(12, transport.observations.get(2).path("totalTokens").asInt());
    }

    @Test
    void textTruncationIsExplicitFailureAndUnsafeConfigurationIsRejected() {
        EvaluationCaptureProperties properties = properties();
        properties.setMaxTextLength(100);
        RecordingTransport transport = new RecordingTransport(properties, mapper);
        ChatEvaluationCollector collector = collector(properties, transport, Runnable::run);
        ChatRequest request = request();
        collector.begin(request);
        collector.complete(request, ChatResponse.success("答".repeat(101), "conversation-test"), null);
        assertEquals("CAPTURE_TRUNCATED", transport.observations.get(0).path("error").asText());
        properties.setHashKey("");
        assertThrows(IllegalArgumentException.class, () -> collector(properties, transport, Runnable::run));
    }

    @Test
    void streamInternalResponseSharesSyncStatusSchemaAndShortIdentifiersAreRedacted() {
        EvaluationCaptureProperties properties = properties();
        RecordingTransport transport = new RecordingTransport(properties, mapper);
        ChatEvaluationCollector collector = collector(properties, transport, Runnable::run);
        ChatRequest request = request();
        request.setCustomerId("123");
        collector.begin(request);
        StreamEvent event = StreamEvent.status("result", "完成", Map.of("taskStatus", "SUCCESS"));
        event.setEvaluationTaskResponse(DoctorAssistantTaskResponse.builder()
                .status(DoctorAssistantTaskStatus.SUCCESS)
                .needsHumanReview(false).build());
        collector.event(request, event);
        collector.complete(request, ChatResponse.success("患者123的记录", "conversation-test"), null);
        JsonNode observation = transport.observations.get(0);
        assertEquals("SUCCESS", observation.at("/output/agentResponse/status").asText());
        assertFalse(observation.at("/output/response/message").asText().contains("123"));
    }

    private ChatEvaluationCollector collector(EvaluationCaptureProperties properties, RecordingTransport transport, Executor executor) {
        return new ChatEvaluationCollector(properties, mapper, transport, new SimpleMeterRegistry(), executor);
    }

    private EvaluationCaptureProperties properties() {
        EvaluationCaptureProperties properties = new EvaluationCaptureProperties();
        properties.setEnabled(true);
        properties.setHashKey("synthetic-test-key-at-least-16");
        return properties;
    }

    private ChatRequest request() {
        ChatRequest request = new ChatRequest();
        request.setCustomerId("patient-001");
        request.setConversationId("conversation-test");
        request.setRequestId("request-test");
        request.setMessage("测试问题");
        request.setEvaluation(new EvaluationBinding("batch-1", "case-1", 1));
        return request;
    }

    private static class RecordingTransport extends EvaluationCaptureTransport {
        final List<JsonNode> observations = new ArrayList<>();
        final List<JsonNode> attempts = new ArrayList<>();
        JsonNode replay;
        String requestedRunId;
        int failures;

        RecordingTransport(EvaluationCaptureProperties properties, ObjectMapper mapper) { super(properties, mapper); }
        @Override public JsonNode replay(String runId) { requestedRunId = runId; return replay; }
        @Override public void publish(String batchId, JsonNode observation) {
            attempts.add(observation.deepCopy());
            if (failures-- > 0) { throw new IllegalStateException("测试超时"); }
            observations.add(observation.deepCopy());
        }
    }
}
