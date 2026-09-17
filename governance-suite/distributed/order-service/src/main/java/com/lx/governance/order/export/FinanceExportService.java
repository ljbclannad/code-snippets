package com.lx.governance.order.export;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.lx.governance.common.auth.AdminRole;
import com.lx.governance.order.adminauth.AdminSearchScope;
import com.lx.governance.order.adminauth.AdminSearchScopeResolver;
import com.lx.governance.order.repo.FinanceExportRepository;
import com.lx.governance.order.observability.AdminOrderActivity;
import com.lx.governance.order.search.OrderOperatorSearchCriteria;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceExportService {
    private final AdminSearchScopeResolver scopeResolver;
    private final FinanceExportRepository repository;
    private final FinanceExportFileStorage fileStorage;
    private final AdminOrderActivity activity;
    private final Clock clock;
    private final FinanceCsvWriter csvWriter = new FinanceCsvWriter();

    @Autowired
    public FinanceExportService(AdminSearchScopeResolver scopeResolver, FinanceExportRepository repository,
            AdminOrderActivity activity,
            @Value("${app.finance-export.directory:${java.io.tmpdir}/order-finance-exports}") String directory) {
        this(scopeResolver, repository, activity, new FinanceExportFileStorage(Path.of(directory)), Clock.systemUTC());
    }

    FinanceExportService(AdminSearchScopeResolver scopeResolver, FinanceExportRepository repository,
            AdminOrderActivity activity, FinanceExportFileStorage fileStorage, Clock clock) {
        this.scopeResolver = scopeResolver;
        this.repository = repository;
        this.activity = activity;
        this.fileStorage = fileStorage;
        this.clock = clock;
    }

    @Transactional(transactionManager = "orderAdminReadModelTransactionManager", isolation = Isolation.REPEATABLE_READ,
            rollbackFor = Exception.class)
    public FinanceExportTask create(String authorization, OrderOperatorSearchCriteria criteria) {
        AdminSearchScope scope = financeScope(authorization);
        String id = UUID.randomUUID().toString();
        FinanceExportSnapshot snapshot = FinanceExportSnapshot.from(scope, criteria, repository.readModelWatermark());
        repository.createAndMaterialize(id, scope.userId(), criteria, snapshot, clock.instant());
        activity.record("EXPORT_CREATED", scope.userId(), id, "SUCCESS", "criteria=" + criteria.fingerprint());
        return taskFor(scope, id);
    }

    public FinanceExportTask get(String authorization, String id) {
        return taskFor(financeScope(authorization), id);
    }

    @Transactional(transactionManager = "orderAdminReadModelTransactionManager", rollbackFor = Exception.class)
    public void retry(String authorization, String id) {
        AdminSearchScope scope = financeScope(authorization);
        FinanceExportTask task = taskFor(scope, id);
        if (task.isExpired(clock.instant())) throw new FinanceExportExpiredException();
        if (!repository.retry(id)) throw new FinanceExportConflictException("export task is not retryable");
        activity.record("EXPORT_RETRIED", scope.userId(), id, "SUCCESS", null);
    }

    public Path download(String authorization, String id) {
        AdminSearchScope scope = financeScope(authorization);
        FinanceExportTask task = taskFor(scope, id);
        if (task.isExpired(clock.instant())) throw new FinanceExportExpiredException();
        if (task.status() != FinanceExportStatus.READY || task.fileKey() == null) {
            throw new FinanceExportConflictException("export task is not ready");
        }
        Path file = fileStorage.read(task.fileKey());
        if (!Files.isRegularFile(file)) throw new FinanceExportConflictException("export file is unavailable");
        activity.record("EXPORT_DOWNLOADED", scope.userId(), id, "SUCCESS", null);
        return file;
    }

    @Scheduled(fixedDelayString = "${app.finance-export.poll-delay-ms:1000}")
    public void processPending() {
        repository.claimNext(clock.instant()).ifPresent(this::generate);
    }

    @Scheduled(fixedDelayString = "${app.finance-export.cleanup-delay-ms:60000}")
    public void cleanupExpired() {
        for (FinanceExportTask task : repository.expire(clock.instant())) {
            activity.recordBackground("EXPORT_EXPIRED", task.id(), "SUCCESS", null);
            try {
                fileStorage.delete(task.fileKey());
                fileStorage.deletePart(task.id());
                repository.deleteRows(task.id());
            } catch (Exception ignored) {
                // 任务已不可访问；下一轮继续清理。
            }
        }
    }

    private void generate(FinanceExportTask task) {
        try (StringWriter writer = new StringWriter()) {
            csvWriter.write(writer, repository.rows(task.id(), task), task.fieldSet());
            Instant completedAt = clock.instant();
            String key = fileStorage.write(task.id(), writer.toString());
            repository.markReady(task.id(), key, completedAt, completedAt.plus(Duration.ofHours(24)));
            activity.recordBackground("EXPORT_COMPLETED", task.id(), "SUCCESS", "rows=" + task.rowCount());
        } catch (Exception exception) {
            repository.markFailed(task.id(), abbreviate(exception.getMessage()));
            activity.recordBackground("EXPORT_FAILED", task.id(), "FAILURE", exception.getClass().getSimpleName());
        }
    }

    private FinanceExportTask taskFor(AdminSearchScope scope, String id) {
        FinanceExportTask task = repository.find(id).orElseThrow(FinanceExportNotFoundException::new);
        if (task.ownerUserId() != scope.userId()) throw new FinanceExportNotFoundException();
        if (task.isExpired(clock.instant())) throw new FinanceExportExpiredException();
        return task;
    }

    private AdminSearchScope financeScope(String authorization) {
        AdminSearchScope scope = scopeResolver.resolve(authorization);
        if (!scope.roles().contains(AdminRole.FINANCE_VIEWER.name())) throw new FinanceExportFinanceRoleRequiredException();
        return scope;
    }

    private String abbreviate(String value) {
        if (value == null || value.isBlank()) return "export generation failed";
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    public static class FinanceExportNotFoundException extends RuntimeException { }
    public static class FinanceExportExpiredException extends RuntimeException { }
    public static class FinanceExportConflictException extends RuntimeException {
        public FinanceExportConflictException(String message) { super(message); }
    }
    public static class FinanceExportFinanceRoleRequiredException extends RuntimeException { }
}
