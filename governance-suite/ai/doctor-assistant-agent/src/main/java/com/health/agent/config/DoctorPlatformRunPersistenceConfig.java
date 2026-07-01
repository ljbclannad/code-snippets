package com.health.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.lx.governance.agentplatform.runtime.ApprovalRepository;
import com.lx.governance.agentplatform.runtime.ArtifactRepository;
import com.lx.governance.agentplatform.runtime.ConversationRunLinkRepository;
import com.lx.governance.agentplatform.runtime.InMemoryRunPersistenceRepository;
import com.lx.governance.agentplatform.runtime.PlatformAuditEventRepository;
import com.lx.governance.agentplatform.runtime.PlatformMetricsService;
import com.lx.governance.agentplatform.runtime.RunEventRepository;
import com.lx.governance.agentplatform.runtime.RunPersistenceRepository;
import com.lx.governance.agentplatform.runtime.RunReplayService;
import com.lx.governance.agentplatform.runtime.RunRepository;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 学习项目阶段默认启用内存版平台 run 仓储。
 */
@Configuration
public class DoctorPlatformRunPersistenceConfig {

    @Bean
    public InMemoryRunPersistenceRepository inMemoryRunPersistenceRepository() {
        return new InMemoryRunPersistenceRepository();
    }

    @Bean
    public RunPersistenceRepository runPersistenceRepository(InMemoryRunPersistenceRepository repository) {
        return repository;
    }

    @Bean
    public RunRepository runRepository(InMemoryRunPersistenceRepository repository) {
        return repository;
    }

    @Bean
    public RunEventRepository runEventRepository(InMemoryRunPersistenceRepository repository) {
        return repository;
    }

    @Bean
    public ArtifactRepository artifactRepository(InMemoryRunPersistenceRepository repository) {
        return repository;
    }

    @Bean
    public ConversationRunLinkRepository conversationRunLinkRepository(InMemoryRunPersistenceRepository repository) {
        return repository;
    }

    @Bean
    public ApprovalRepository<com.lx.governance.agentplatform.model.ApprovalTask> platformApprovalTaskRepository(
            InMemoryRunPersistenceRepository repository) {
        return repository;
    }

    @Bean
    public PlatformAuditEventRepository platformAuditEventRepository(InMemoryRunPersistenceRepository repository) {
        return repository;
    }

    @Bean
    public PlatformMetricsService platformMetricsService(MeterRegistry meterRegistry) {
        return new PlatformMetricsService(meterRegistry);
    }

    @Bean
    public RunReplayService runReplayService(
            RunPersistenceRepository runPersistenceRepository,
            RunRepository runRepository,
            RunEventRepository runEventRepository,
            ArtifactRepository artifactRepository,
            ConversationRunLinkRepository conversationRunLinkRepository) {
        return new RunReplayService(
                runPersistenceRepository,
                runRepository,
                runEventRepository,
                artifactRepository,
                conversationRunLinkRepository);
    }
}
