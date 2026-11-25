package com.lx.governance.aichat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.StreamEvent;
import com.lx.governance.aichat.metrics.AiChatMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 真实聊天入口 + 随访 HTTP 替身 + H2 历史 + Redis 替身，验证跨入口重试和续聊状态。 */
class HighRiskFollowUpConversationTest {
    private JdbcTemplate jdbc;
    private HighRiskFollowUpHistoryService history;
    private SessionContextService sessions;
    private HighRiskFollowUpChat followUp;
    private MockRestServiceServer remote;
    private AiChatStreamService stream;
    private AiChatStreamServiceTest streamFixture;
    private final Map<String, String> contextStore = new ConcurrentHashMap<>();
    private final DeepSeekIntentAnalyzer analyzer = mock(DeepSeekIntentAnalyzer.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final String openUrl = "http://doctor/internal/patient-tasks/high-risk-follow-up";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                CREATE TABLE t_ai_chat_history (id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    customer_id VARCHAR(64), conversation_id VARCHAR(128), user_message TEXT,
                    ai_response TEXT, create_time TIMESTAMP, is_deleted INT)
                """);
        // 复用迁移的字段与索引定义；H2 不支持 MySQL 的多 ADD 和列级字符集声明。
        String migration = Files.readString(Path.of("sql/migrations/2026-11-25-follow-up-chat-history.sql"));
        String additions = migration.substring(migration.indexOf("    ADD COLUMN"))
                .replace(" CHARACTER SET ascii COLLATE ascii_bin", "").replace(";", "");
        for (String addition : additions.split(",\\s*\\n")) {
            jdbc.execute("ALTER TABLE t_ai_chat_history " + addition.strip());
        }
        history = new HighRiskFollowUpHistoryService(jdbc);
        sessions = new SessionContextService();
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> contextStore.get(call.getArgument(0)));
        doAnswer(call -> { contextStore.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        ReflectionTestUtils.setField(sessions, "redisTemplate", redis);
        ReflectionTestUtils.setField(sessions, "intentAnalyzer", analyzer);
        var builder = RestClient.builder();
        remote = MockRestServiceServer.bindTo(builder).build();
        var dispatch = mock(AgentDispatchPolicy.class);
        when(dispatch.evaluate(any(), any(), any())).thenReturn(new AgentDispatchPolicy.Decision(true, "allowed"));
        followUp = new HighRiskFollowUpChat(builder.build(), new DoctorAssistantAgentEndpoint(
                mock(LoadBalancerClient.class), "doctor", "http://doctor"), mock(AiChatMetrics.class),
                dispatch, true, "test-token", history, sessions);
        streamFixture = new AiChatStreamServiceTest();
        streamFixture.setUp();
        stream = (AiChatStreamService) ReflectionTestUtils.getField(streamFixture, "aiChatStreamService");
        ReflectionTestUtils.setField(stream, "highRiskFollowUp", followUp);
    }

    @Test
    void timeoutThenStreamRetryAndSyncReplayKeepOnePersistentTurn() throws Exception {
        remote.expect(requestTo(openUrl)).andExpect(jsonPath("$.requestId").value("r1"))
                .andRespond(withGatewayTimeout());
        remote.expect(requestTo(openUrl)).andExpect(jsonPath("$.requestId").value("r1"))
                .andRespond(withSuccess("{\"taskId\":7,\"status\":\"PENDING\"}", MediaType.APPLICATION_JSON));
        remote.expect(requestTo(openUrl)).andExpect(jsonPath("$.requestId").value("r1"))
                .andRespond(withSuccess("{\"taskId\":7,\"status\":\"APPROVED\"}", MediaType.APPLICATION_JSON));
        remote.expect(requestTo(openUrl)).andRespond(withGatewayTimeout());

        assertTrue(streamReply(request("r1")).contains("无法确认"));
        long originalTime = storedTime();
        assertTrue(streamReply(request("r1")).contains("等待责任医生处理"));
        var fixture = new AiChatServiceConcurrencyTest();
        fixture.setUp();
        try {
            var sync = (AiChatService) ReflectionTestUtils.getField(fixture, "aiChatService");
            ReflectionTestUtils.setField(sync, "highRiskFollowUp", followUp);
            assertTrue(sync.handleChatRequest(request("r1")).get(3, TimeUnit.SECONDS).getMessage().contains("医生已确认"));
        } finally {
            fixture.tearDown();
        }
        assertTrue(streamReply(request("r1")).contains("无法确认"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_ai_chat_history", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT ai_response FROM t_ai_chat_history", String.class).contains("医生已确认"));
        assertEquals(originalTime, storedTime());
        SessionContext context = context();
        assertEquals(2, context.getMessageCount());
        assertEquals(1, context.getRecentSummaries().size());
        assertEquals("胸痛", context.getRecentSummaries().get(0).getUserMessage());
        assertEquals("APPROVED", context.getExtractedData().get("followUpTaskStatus"));
        assertEquals(7, ((Number) context.getExtractedData().get("followUpTaskId")).intValue());
        verifyNoInteractions(analyzer);
        verifyNoInteractions(ReflectionTestUtils.getField(streamFixture, "deepSeekClient"),
                ReflectionTestUtils.getField(streamFixture, "chatAsyncProcessingService"));
        remote.verify();
    }

    @Test
    void firstSyncReplyIsPersistedBeforeReturningAndOldRetryCannotReplaceNewerContext() throws Exception {
        remote.expect(requestTo(openUrl)).andRespond(withSuccess("{\"taskId\":7,\"status\":\"PENDING\"}", MediaType.APPLICATION_JSON));
        remote.expect(requestTo(openUrl)).andRespond(withSuccess("{\"taskId\":7,\"status\":\"APPROVED\"}", MediaType.APPLICATION_JSON));
        var fixture = new AiChatServiceConcurrencyTest();
        fixture.setUp();
        try {
            var sync = (AiChatService) ReflectionTestUtils.getField(fixture, "aiChatService");
            ReflectionTestUtils.setField(sync, "highRiskFollowUp", followUp);
            var reply = sync.handleChatRequest(request("r1")).get(3, TimeUnit.SECONDS);
            assertEquals(reply.getMessage(), jdbc.queryForObject("SELECT ai_response FROM t_ai_chat_history", String.class));
            assertEquals("胸痛", context().getRecentSummaries().get(0).getUserMessage());
        } finally {
            fixture.tearDown();
        }
        long newerTime = storedTime() + 100;
        sessions.updateContext("c", "现在好一点了", "请继续观察", "newer", newerTime);
        streamReply(request("r1"));
        assertEquals(newerTime, context().getLastMessageAt());
        assertEquals("newer", context().getLastProcessedRequestId());
        assertEquals(2, context().getRecentSummaries().size());
        verifyNoInteractions(analyzer);
        remote.verify();
    }

    @Test
    void statusQueryRetriesReadLiveProgressWithoutCreatingTask() throws Exception {
        String url = "http://doctor/internal/patient-tasks/review-status?customerId=p&conversationId=c";
        remote.expect(requestTo(url)).andRespond(withGatewayTimeout());
        remote.expect(requestTo(url)).andRespond(withSuccess("{\"status\":\"APPROVED\",\"delivered\":true}", MediaType.APPLICATION_JSON));
        var request = request("status");
        request.setMessage("刚才那条医生看了吗？");
        assertTrue(streamReply(request).contains("无法确认"));
        assertTrue(streamReply(request).contains("已送达"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_ai_chat_history", Integer.class));
        assertEquals(1, context().getRecentSummaries().size());
        verifyNoInteractions(analyzer);
        remote.verify();
    }

    @Test
    void concurrentHistoryWritesAndFreshWriterKeepOneTurn() throws Exception {
        var request = request("r1");
        request.setMessageTimestamp(100L);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { start.await(); return history.record(request, "确认回复", true); });
            var second = pool.submit(() -> { start.await(); return history.record(request, "无法确认", false); });
            start.countDown();
            assertEquals(100L, first.get(3, TimeUnit.SECONDS));
            assertEquals(100L, second.get(3, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        request.setMessageTimestamp(200L);
        assertEquals(100L, new HighRiskFollowUpHistoryService(jdbc).record(request, "批准回复", true));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_ai_chat_history", Integer.class));
        assertEquals("批准回复", jdbc.queryForObject("SELECT ai_response FROM t_ai_chat_history", String.class));
    }

    @Test
    void persistenceFailurePreservesSafetyReplyAndRetryRepairsHistory() throws Exception {
        var failingHistory = spy(history);
        doThrow(new IllegalStateException("模拟存储故障")).doCallRealMethod()
                .when(failingHistory).record(any(), anyString(), anyBoolean());
        ReflectionTestUtils.setField(followUp, "history", failingHistory);
        remote.expect(requestTo(openUrl)).andRespond(withSuccess("{\"taskId\":7,\"status\":\"PENDING\"}", MediaType.APPLICATION_JSON));
        remote.expect(requestTo(openUrl)).andRespond(withSuccess("{\"taskId\":7,\"status\":\"PENDING\"}", MediaType.APPLICATION_JSON));
        assertTrue(streamReply(request("r1")).contains("请立即就医"));
        assertNotNull(context());
        assertTrue(streamReply(request("r1")).contains("已为你登记"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_ai_chat_history", Integer.class));
        assertEquals(1, context().getRecentSummaries().size());
        remote.verify();
    }

    @Test
    void contextFailurePreservesReplyAndSameRequestRepairsContext() throws Exception {
        var failingSessions = spy(sessions);
        doThrow(new IllegalStateException("模拟上下文故障")).doCallRealMethod()
                .when(failingSessions).recordFollowUpTurn(any(), anyLong(), any(), any());
        ReflectionTestUtils.setField(followUp, "sessions", failingSessions);
        remote.expect(requestTo(openUrl)).andRespond(withSuccess("{\"taskId\":7,\"status\":\"PENDING\"}", MediaType.APPLICATION_JSON));
        remote.expect(requestTo(openUrl)).andRespond(withSuccess("{\"taskId\":7,\"status\":\"PENDING\"}", MediaType.APPLICATION_JSON));
        assertTrue(streamReply(request("r1")).contains("请立即就医"));
        assertTrue(contextStore.isEmpty());
        assertTrue(streamReply(request("r1")).contains("已为你登记"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_ai_chat_history", Integer.class));
        assertEquals(1, context().getRecentSummaries().size());
        remote.verify();
    }

    private String streamReply(ChatRequest request) {
        List<StreamEvent> events = stream.handleChatStreamRequest(request).collectList().block(Duration.ofSeconds(3));
        assertEquals(StreamEvent.EventType.DONE, events.get(events.size() - 1).getType());
        return events.stream().filter(event -> event.getType() == StreamEvent.EventType.CONTENT)
                .map(StreamEvent::getContent).reduce("", String::concat);
    }

    private ChatRequest request(String id) {
        var request = new ChatRequest();
        request.setCustomerId("p");
        request.setGroupId("g");
        request.setConversationId("c");
        request.setRequestId(id);
        request.setMessage("胸痛");
        request.setVerifiedPatient(true);
        return request;
    }

    private long storedTime() {
        return jdbc.queryForObject("SELECT follow_up_received_at FROM t_ai_chat_history", Long.class);
    }

    private SessionContext context() throws Exception {
        return mapper.readValue(contextStore.get("ai:session:owned-context:c"), SessionContext.class);
    }
}
