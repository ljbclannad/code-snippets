package com.lx.governance.order.rebuild;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.auth.AdminRole;
import com.lx.governance.order.adminauth.AdminSearchScope;
import com.lx.governance.order.adminauth.AdminSearchScopeResolver;
import com.lx.governance.order.adminauth.OrderOperatorRequiredException;
import com.lx.governance.order.domain.PaymentProjectionEvent;
import com.lx.governance.order.observability.AdminOrderActivity;
import com.lx.governance.order.service.OrderAdminProjectionService;
import com.lx.governance.order.service.PaymentAdminProjectionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectionRebuildService {
    private static final int PAGE_SIZE = 100;
    private final AdminSearchScopeResolver scopeResolver;
    private final ProjectionRebuildTaskRepository tasks;
    private final OrderRebuildFactRepository orders;
    private final PaymentRebuildClient payments;
    private final OrderAdminProjectionService orderProjection;
    private final PaymentAdminProjectionService paymentProjection;
    private final AdminOrderActivity activity;
    private final Clock clock = Clock.systemUTC();

    public ProjectionRebuildService(AdminSearchScopeResolver scopeResolver, ProjectionRebuildTaskRepository tasks,
            OrderRebuildFactRepository orders, PaymentRebuildClient payments,
            OrderAdminProjectionService orderProjection, PaymentAdminProjectionService paymentProjection,
            AdminOrderActivity activity) {
        this.scopeResolver = scopeResolver;
        this.tasks = tasks;
        this.orders = orders;
        this.payments = payments;
        this.orderProjection = orderProjection;
        this.paymentProjection = paymentProjection;
        this.activity = activity;
    }

    @Transactional(transactionManager = "orderAdminReadModelTransactionManager", rollbackFor = Exception.class)
    public ProjectionRebuildTask create(String authorization) {
        AdminSearchScope scope = operator(authorization);
        PaymentRebuildClient.Watermark paymentWatermark = data(payments.watermark());
        if (paymentWatermark.shard0() < 0 || paymentWatermark.shard1() < 0) throw new IllegalStateException("invalid payment watermark");
        String id = UUID.randomUUID().toString();
        tasks.create(id, scope.userId(), orders.watermark(0), orders.watermark(1), paymentWatermark.shard0(),
                paymentWatermark.shard1(), clock.instant());
        activity.record("REBUILD_CREATED", scope.userId(), id, "SUCCESS", null);
        return tasks.find(id).orElseThrow();
    }

    public ProjectionRebuildTask get(String authorization, String id) {
        operator(authorization);
        return tasks.find(id).orElseThrow(RebuildNotFoundException::new);
    }

    @Transactional(transactionManager = "orderAdminReadModelTransactionManager", rollbackFor = Exception.class)
    public void retry(String authorization, String id) {
        AdminSearchScope scope = operator(authorization);
        if (!tasks.retry(id, scope.userId(), clock.instant())) {
            throw new RebuildConflictException();
        }
        activity.record("REBUILD_RETRIED", scope.userId(), id, "SUCCESS", null);
    }

    @Scheduled(fixedDelayString = "${app.projection-rebuild.poll-delay-ms:1000}")
    public void processPending() {
        for (String id : tasks.failStale(clock.instant())) {
            activity.recordBackground("REBUILD_FAILED", id, "FAILURE", "WorkerLeaseExpired");
        }
        tasks.claim(clock.instant()).ifPresent(task -> {
            try {
                ProjectionRebuildTask current = task;
                while (current.status().equals("RUNNING")) {
                    process(current);
                    current = tasks.find(task.id()).orElseThrow();
                }
            } catch (Exception exception) {
                String message = exception.getMessage();
                tasks.fail(task.id(), message == null ? "rebuild failed" : message.substring(0, Math.min(500, message.length())),
                        clock.instant());
                activity.recordBackground("REBUILD_FAILED", task.id(), "FAILURE", exception.getClass().getSimpleName());
            }
        });
    }

    private void process(ProjectionRebuildTask task) {
        int phase = task.phase();
        if (phase >= 4) {
            tasks.finish(task.id(), clock.instant());
            activity.recordBackground("REBUILD_COMPLETED", task.id(), "SUCCESS", "processed=" + task.processedCount());
            return;
        }
        if (phase < 2) {
            List<OrderRebuildFactRepository.OrderFact> page = orders.page(phase, task.cursor(), task.watermark(), PAGE_SIZE);
            for (OrderRebuildFactRepository.OrderFact fact : page) {
                orderProjection.project(fact.event());
                tasks.advance(task.id(), phase, fact.id(), true, clock.instant());
            }
            if (page.size() < PAGE_SIZE) tasks.nextPhase(task.id(), phase, clock.instant());
        } else {
            PaymentRebuildClient.Page page = data(payments.page(phase - 2, task.cursor(), task.watermark(), PAGE_SIZE));
            if (page.items() == null || page.items().size() > PAGE_SIZE) throw new IllegalStateException("invalid payment rebuild page");
            long previousId = task.cursor();
            for (PaymentRebuildClient.Fact fact : page.items()) {
                if (fact.id() <= previousId || fact.id() > task.watermark()) throw new IllegalStateException("invalid payment fact id");
                if (!validPaymentFact(fact)) {
                    tasks.advance(task.id(), phase, fact.id(), false, clock.instant());
                    activity.recordBackground("REBUILD_FACT_SKIPPED", task.id(), "FAILURE", "payment-id=" + fact.id());
                    previousId = fact.id();
                    continue;
                }
                Instant updatedAt = fact.updatedAt();
                paymentProjection.project(new PaymentProjectionEvent("rebuild-payment-" + fact.id(), fact.orderNo(),
                        fact.payNo(), fact.status(), fact.version(), updatedAt, updatedAt, "projection-rebuild"));
                tasks.advance(task.id(), phase, fact.id(), true, clock.instant());
                previousId = fact.id();
            }
            if (page.items().size() < PAGE_SIZE) tasks.nextPhase(task.id(), phase, clock.instant());
        }
    }

    private boolean validPaymentFact(PaymentRebuildClient.Fact fact) {
        return fact.orderNo() != null && !fact.orderNo().isBlank()
                && fact.payNo() != null && !fact.payNo().isBlank()
                && fact.status() != null && !fact.status().isBlank()
                && fact.version() > 0 && fact.updatedAt() != null;
    }

    private AdminSearchScope operator(String authorization) {
        AdminSearchScope scope = scopeResolver.resolve(authorization);
        if (!scope.roles().contains(AdminRole.ORDER_OPERATOR.name())) throw new OrderOperatorRequiredException();
        return scope;
    }

    private <T> T data(ApiResponse<T> response) {
        if (response == null || !response.isSuccess() || response.getData() == null) {
            throw new IllegalStateException("payment rebuild service unavailable");
        }
        return response.getData();
    }

    public static class RebuildNotFoundException extends RuntimeException { }
    public static class RebuildConflictException extends RuntimeException { }
}
