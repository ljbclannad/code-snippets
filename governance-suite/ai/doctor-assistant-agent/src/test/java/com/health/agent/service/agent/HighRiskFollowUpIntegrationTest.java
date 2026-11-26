package com.health.agent.service.agent;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.health.agent.config.FollowUpReminderProperties;
import com.health.agent.controller.AgentController;
import com.health.agent.controller.AgentAccessGuard;
import com.health.agent.controller.PatientTaskServiceAuth;
import com.health.agent.controller.FollowUpReminderController;
import com.health.agent.controller.DoctorFollowUpController;
import com.health.agent.controller.GlobalExceptionHandler;
import com.health.agent.mapper.PendingTaskMapper;
import com.health.agent.learning.mapper.AgentRunOutcomeMapper;
import com.health.agent.learning.service.DefaultAgentOutcomeRecorder;
import com.lx.governance.agentplatform.runtime.ApprovalResumeExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(HighRiskFollowUpIntegrationTest.Config.class)
class HighRiskFollowUpIntegrationTest {
    @TestConfiguration
    @EnableTransactionManagement(proxyTargetClass = true)
    @ImportAutoConfiguration(MybatisPlusAutoConfiguration.class)
    @MapperScan(basePackageClasses = {PendingTaskMapper.class, AgentRunOutcomeMapper.class})
    @Import({HighRiskFollowUpService.class, AgentSessionService.class, AgentSessionWorkflowService.class,
            PendingTaskService.class, AgentAuditService.class, AgentAccessGuard.class, DefaultAgentOutcomeRecorder.class})
    static class Config {
        @Bean DataSource dataSource() throws Exception {
            String url = System.getProperty("followUpTest.jdbcUrl", "jdbc:h2:mem:reminder;MODE=MySQL;DB_CLOSE_DELAY=-1");
            var ds = new DriverManagerDataSource(url, System.getProperty("followUpTest.user", "sa"),
                    System.getenv().getOrDefault("FOLLOW_UP_TEST_DB_PASSWORD", ""));
            var jdbc = new JdbcTemplate(ds);
            String schema = Files.readString(Path.of("sql/schema.sql"));
            for (String name : List.of("agent_session", "agent_pending_task", "agent_execution_log", "agent_follow_up_reminder", "agent_run_outcome")) {
                int start = schema.indexOf("CREATE TABLE " + (name.equals("agent_follow_up_reminder") ? "IF NOT EXISTS " : "") + name + " (");
                String ddl = schema.substring(start, schema.indexOf(';', start));
                if (url.startsWith("jdbc:h2:")) {
                    // H2 的索引名属于整个库，JSON 参数的 JDBC 字符串绑定也与 MySQL 不同。
                    ddl = ddl.replaceAll(",\\s*INDEX [^(]+\\([^)]*\\)", "").replace(" JSON ", " TEXT ")
                            .replace(" CHARACTER SET utf8mb4 COLLATE utf8mb4_bin", "");
                }
                jdbc.execute(ddl);
            }
            return ds;
        }

        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean ApprovalResumeExecutor<AgentApprovalResumeState> resumeExecutor() {
            return (session, task) -> { throw new AssertionError("随访提醒不能恢复模型执行"); };
        }
    }

    @Autowired HighRiskFollowUpService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentSessionService sessions;
    @Autowired PendingTaskService tasks;
    @Autowired AgentAccessGuard accessGuard;
    @Autowired DataSourceTransactionManager transactions;
    MockMvc mvc;
    String patient = UUID.randomUUID().toString();

    @BeforeEach void setUp() {
        var properties = new FollowUpReminderProperties();
        properties.getDoctors().put(patient, "doctor-a");
        var agent = new AgentController();
        ReflectionTestUtils.setField(agent, "taskService", tasks);
        ReflectionTestUtils.setField(agent, "sessionService", sessions);
        ReflectionTestUtils.setField(agent, "reminders", service);
        mvc = MockMvcBuilders.standaloneSetup(agent, new FollowUpReminderController(service, properties, accessGuard, new PatientTaskServiceAuth("test-token")),
                        new DoctorFollowUpController(tasks, service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void demoPagesObserveApprovedAndRejectedFollowUpsWithRoleIsolation() throws Exception {
        String queue = "/api/doctor/follow-up-tasks";
        String progress = "/internal/patient-tasks/follow-up-status";
        String inbox = "/internal/patient-tasks/follow-up-inbox";
        long approved = open().taskId();
        assertEquals(approved, open().taskId());
        long rejected = service.open(patient, "r2", "c2", "第二个合成样本", "doctor-a").taskId();
        mvc.perform(get(queue)).andExpect(status().isUnauthorized());
        mvc.perform(get(queue).with(actor(patient, "PATIENT"))).andExpect(status().isForbidden());
        mvc.perform(get(queue).with(actor("doctor-b", "DOCTOR"))).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get(queue + "/{id}", approved).with(actor("doctor-b", "DOCTOR"))).andExpect(status().isForbidden());
        mvc.perform(get(queue).with(actor("doctor-a", "DOCTOR"))).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(post(queue + "/{id}/reject", rejected).with(actor("doctor-a", "DOCTOR"))
                        .contentType("application/json").content("{\"comment\":\" \"}"))
                .andExpect(status().isBadRequest());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(queue + "/{id}/approve", approved).with(actor("doctor-a", "DOCTOR"))
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        }
        mvc.perform(post(queue + "/{id}/reject", rejected).with(actor("doctor-a", "DOCTOR"))
                        .contentType("application/json").content("{\"comment\":\"合成样本无需投递\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(get(queue).with(actor("doctor-a", "DOCTOR"))).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get(progress).header("X-Follow-Up-Token", "test-token").param("customerId", patient).param("conversationId", "c1"))
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.delivered").value(true));
        mvc.perform(get(progress).header("X-Follow-Up-Token", "test-token").param("customerId", patient).param("conversationId", "c2"))
                .andExpect(jsonPath("$.status").value("REJECTED")).andExpect(jsonPath("$.delivered").value(false));
        mvc.perform(get(inbox).header("X-Follow-Up-Token", "test-token").param("customerId", patient).param("beforeId", Long.toString(Long.MAX_VALUE)))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].receiptId").value(Long.toString(approved)))
                .andExpect(jsonPath("$[0].reviewComment").doesNotExist());
    }

    @Test void workbenchApprovalDeliversOnlyToOwner() throws Exception {
        String trigger = """
                {"customerId":"%s","requestId":"r1","conversationId":"c1","patientMessage":"合成高风险样本"}
                """.formatted(patient);
        var url = "/internal/patient-tasks/high-risk-follow-up";
        mvc.perform(post(url).contentType("application/json").content(trigger)).andExpect(status().isForbidden());
        mvc.perform(post(url).header("X-Follow-Up-Token", "test-token").contentType("application/json").content(trigger))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        long id = open().taskId();
        mvc.perform(get("/api/agent/pending-tasks/{id}", id).with(actor("doctor-a", "DOCTOR")))
                .andExpect(jsonPath("$.inputJson").value(org.hamcrest.Matchers.containsString("合成高风险样本")));
        mvc.perform(get("/api/agent/pending-tasks/{id}", id).with(actor("doctor-b", "DOCTOR")))
                .andExpect(status().isForbidden());
        String session = tasks.getById(id).getAgentSessionId();
        mvc.perform(get("/api/agent/sessions/{session}", session).with(actor("doctor-b", "DOCTOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/agent/sessions/customer/{customer}", patient))
                .andExpect(jsonPath("$").isEmpty());
        String approve = "/api/agent/pending-tasks/" + id + "/approve";
        mvc.perform(post(approve).with(actor("doctor-b", "DOCTOR")).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(approve).with(actor("doctor-a", "PATIENT")).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(approve).with(actor("doctor-a", "DOCTOR")).contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        String inbox = "/api/patient/follow-up-reminders";
        mvc.perform(get(inbox)).andExpect(status().isUnauthorized());
        mvc.perform(get(inbox).with(actor("other", "PATIENT"))).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get(inbox).with(actor(patient, "PATIENT"))).andExpect(jsonPath("$[0].receiptId").value(id))
                .andExpect(jsonPath("$[0].message").isString()).andExpect(jsonPath("$[0].patientMessage").doesNotExist());
    }

    @Test void concurrentCreationAndApprovalProduceOneReceipt() throws Exception {
        var opened = concurrently(this::open);
        assertEquals(opened.get(0), opened.get(1));
        long id = opened.get(0).taskId();
        assertTrue(service.inbox(patient, Long.MAX_VALUE).isEmpty());
        var receipts = concurrently(() -> service.review(id, "doctor-a", true, "同意"));
        assertEquals(receipts.get(0), receipts.get(1));
        assertEquals(List.of(id), service.inbox(patient, Long.MAX_VALUE).stream()
                .map(HighRiskFollowUpService.InboxMessage::receiptId).toList());
        assertEquals("APPROVED", open().status());
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT approval_status FROM agent_execution_log WHERE pending_task_id = ?", String.class, id));
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT status FROM agent_session WHERE id = ?", String.class, tasks.getById(id).getAgentSessionId()));
    }

    @Test void rejectedAndRolledBackActionsDoNotDeliver() {
        long id = open().taskId();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.review(id, "doctor-a", true, "同意");
            tx.setRollbackOnly();
        });
        assertEquals("PENDING", open().status());
        assertTrue(service.inbox(patient, Long.MAX_VALUE).isEmpty());
        assertEquals("REJECTED", service.review(id, "doctor-a", false, "无需提醒").status());
        assertEquals("REJECTED", service.review(id, "doctor-a", false, "重试").status());
        assertThrows(ResponseStatusException.class, () -> service.review(id, "doctor-a", true, "再次批准"));
        assertThrows(ResponseStatusException.class, () -> service.open(patient, "r1", "c1", "不同原文", "doctor-a"));
        assertTrue(service.inbox(patient, Long.MAX_VALUE).isEmpty());
    }

    @Test void subsequentTurnsReadLatestCommittedTaskForSamePatientAndConversation() throws Exception {
        String url = "/internal/patient-tasks/review-status";
        mvc.perform(get(url).param("customerId", patient).param("conversationId", "c1"))
                .andExpect(status().isForbidden());
        long first = open().taskId();
        assertEquals("NONE", service.latestProgress(patient, "other").status());
        assertEquals("NONE", service.latestProgress("other", "c1").status());
        assertEquals("PENDING", service.latestProgress(patient, "c1").status());
        service.review(first, "doctor-a", true, "同意");
        mvc.perform(get(url).header("X-Follow-Up-Token", "test-token").param("customerId", patient)
                        .param("conversationId", "c1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.delivered").value(true)).andExpect(jsonPath("$.taskId").doesNotExist());
        long second = service.open(patient, "r2", "c1", "另一次测试", "doctor-a").taskId();
        service.review(second, "doctor-a", false, "无需投递");
        assertEquals(new HighRiskFollowUpService.ReviewProgress("REJECTED", false), service.latestProgress(patient, "c1"));
        var ordinary = sessions.createSession(patient, "doctor-a", "普通随访", "c1");
        jdbc.update("""
                INSERT INTO agent_pending_task (agent_session_id, customer_id, doctor_id, tool_name, input_json, status)
                VALUES (?, ?, 'doctor-a', 'patient_follow_up_review', '{}', 'APPROVED')
                """, ordinary.getId(), patient);
        assertEquals(new HighRiskFollowUpService.ReviewProgress("APPROVED", false), service.latestProgress(patient, "c1"));
        assertEquals(new HighRiskFollowUpService.ReviewProgress("REJECTED", false), service.latestFollowUp(patient, "c1"));
    }

    private HighRiskFollowUpService.Receipt open() {
        return service.open(patient, "r1", "c1", "合成高风险样本", "doctor-a");
    }

    private static RequestPostProcessor actor(String name, String role) {
        return request -> { request.setUserPrincipal(() -> name); request.addUserRole(role); return request; };
    }

    private static <T> List<T> concurrently(Callable<T> action) throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<T> work = () -> { start.await(); return action.call(); };
            var first = pool.submit(work);
            var second = pool.submit(work);
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
