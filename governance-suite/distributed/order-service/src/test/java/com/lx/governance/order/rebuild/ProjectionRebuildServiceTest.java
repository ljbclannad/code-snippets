package com.lx.governance.order.rebuild;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.order.adminauth.AdminSearchScope;
import com.lx.governance.order.adminauth.AdminSearchScopeResolver;
import com.lx.governance.order.adminauth.OrderOperatorRequiredException;
import com.lx.governance.order.domain.PaymentProjectionEvent;
import com.lx.governance.order.service.OrderAdminProjectionService;
import com.lx.governance.order.service.PaymentAdminProjectionService;
import com.lx.governance.order.observability.AdminOrderActivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProjectionRebuildServiceTest {
    private AdminSearchScopeResolver scopeResolver;
    private ProjectionRebuildTaskRepository tasks;
    private OrderRebuildFactRepository orders;
    private PaymentRebuildClient payments;
    private PaymentAdminProjectionService paymentProjection;
    private ProjectionRebuildService service;

    @BeforeEach
    void setUp() {
        scopeResolver = mock(AdminSearchScopeResolver.class);
        tasks = mock(ProjectionRebuildTaskRepository.class);
        orders = mock(OrderRebuildFactRepository.class);
        payments = mock(PaymentRebuildClient.class);
        paymentProjection = mock(PaymentAdminProjectionService.class);
        service = new ProjectionRebuildService(scopeResolver, tasks, orders, payments,
                mock(OrderAdminProjectionService.class), paymentProjection, mock(AdminOrderActivity.class));
    }

    @Test
    void rejectsNonOperatorBeforeReadingPaymentFactsOrTasks() {
        when(scopeResolver.resolve("finance")).thenReturn(new AdminSearchScope(7, "finance", Set.of("FINANCE_VIEWER")));

        assertThatThrownBy(() -> service.create("finance")).isInstanceOf(OrderOperatorRequiredException.class);
        assertThatThrownBy(() -> service.get("finance", "task")).isInstanceOf(OrderOperatorRequiredException.class);
        assertThatThrownBy(() -> service.retry("finance", "task")).isInstanceOf(OrderOperatorRequiredException.class);
        verify(payments, never()).watermark();
        verify(tasks, never()).find(any());
    }

    @Test
    void freezesBothServicesShardWatermarksForOperator() {
        when(scopeResolver.resolve("operator")).thenReturn(new AdminSearchScope(9, "operator", Set.of("ORDER_OPERATOR")));
        when(payments.watermark()).thenReturn(ApiResponse.ok(new PaymentRebuildClient.Watermark(31, 42), "trace"));
        when(orders.watermark(0)).thenReturn(11L);
        when(orders.watermark(1)).thenReturn(22L);
        when(tasks.find(any())).thenAnswer(invocation -> Optional.of(new ProjectionRebuildTask(
                invocation.getArgument(0), 9, "PENDING", 0, 11, 22, 31, 42, 0, 0, 0, 0, 0, 0, 0,
                null, Instant.EPOCH, Instant.EPOCH)));

        service.create("operator");

        verify(tasks).create(any(), anyLong(), org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.eq(22L), org.mockito.ArgumentMatchers.eq(31L),
                org.mockito.ArgumentMatchers.eq(42L), any());
    }

    @Test
    void retryKeepsOriginalTaskWatermarksAndRecordsOperator() {
        when(scopeResolver.resolve("operator")).thenReturn(new AdminSearchScope(9, "operator", Set.of("ORDER_OPERATOR")));
        when(tasks.retry(org.mockito.ArgumentMatchers.eq("task"), org.mockito.ArgumentMatchers.eq(9L), any()))
                .thenReturn(true);

        service.retry("operator", "task");

        verify(tasks).retry(org.mockito.ArgumentMatchers.eq("task"), org.mockito.ArgumentMatchers.eq(9L), any());
        verify(payments, never()).watermark();
    }

    @Test
    void replaysPaymentPageThroughExistingVersionedProjectionAndCompletes() {
        ProjectionRebuildTask running = task("RUNNING", 3);
        when(tasks.claim(any())).thenReturn(Optional.of(running));
        when(payments.page(1, 0, 42, 100)).thenReturn(ApiResponse.ok(new PaymentRebuildClient.Page(
                List.of(new PaymentRebuildClient.Fact(12, "O-12", "P-12", "SUCCEEDED", 3,
                        Instant.parse("2026-09-17T10:00:00Z")))), "trace"));
        when(tasks.find("task")).thenReturn(Optional.of(task("RUNNING", 4)), Optional.of(task("COMPLETED", 4)));

        service.processPending();

        ArgumentCaptor<PaymentProjectionEvent> event = ArgumentCaptor.forClass(PaymentProjectionEvent.class);
        verify(paymentProjection).project(event.capture());
        org.assertj.core.api.Assertions.assertThat(event.getValue().paymentVersion()).isEqualTo(3);
        verify(tasks).advance(org.mockito.ArgumentMatchers.eq("task"), org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.eq(12L), org.mockito.ArgumentMatchers.eq(true), any());
        verify(tasks).finish(org.mockito.ArgumentMatchers.eq("task"), any());
    }

    @Test
    void skipsMalformedHistoricPaymentFactWithoutChangingPaymentSource() {
        ProjectionRebuildTask running = task("RUNNING", 3);
        when(tasks.claim(any())).thenReturn(Optional.of(running));
        when(payments.page(1, 0, 42, 100)).thenReturn(ApiResponse.ok(new PaymentRebuildClient.Page(
                List.of(new PaymentRebuildClient.Fact(14, "", "P-14", "PREPARED", 1,
                        Instant.parse("2026-09-17T10:00:00Z")))), "trace"));
        when(tasks.find("task")).thenReturn(Optional.of(task("RUNNING", 4)), Optional.of(task("COMPLETED", 4)));

        service.processPending();

        verify(tasks).advance(org.mockito.ArgumentMatchers.eq("task"), org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.eq(14L), org.mockito.ArgumentMatchers.eq(false), any());
        verifyNoInteractions(paymentProjection);
        verify(tasks).finish(org.mockito.ArgumentMatchers.eq("task"), any());
    }

    private ProjectionRebuildTask task(String status, int phase) {
        return new ProjectionRebuildTask("task", 9, status, phase, 11, 22, 31, 42, 0, 0, 0, 0, 0, 0, 0,
                null, Instant.EPOCH, Instant.EPOCH);
    }
}
