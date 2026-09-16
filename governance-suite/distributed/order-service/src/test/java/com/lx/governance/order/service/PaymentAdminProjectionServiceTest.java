package com.lx.governance.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import com.lx.governance.order.domain.PaymentProjectionEvent;
import com.lx.governance.order.repo.OrderAdminReadModelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentAdminProjectionServiceTest {
    @Mock
    private OrderAdminReadModelRepository repository;
    @InjectMocks
    private PaymentAdminProjectionService service;

    @Test
    void projectsVersionedPaymentSnapshot() {
        PaymentProjectionEvent event = event("SUCCEEDED", 2);
        when(repository.upsertPaymentIfNewer(event)).thenReturn(1);

        assertThat(service.project(event)).isTrue();

        verify(repository).upsertPaymentIfNewer(event);
    }

    @Test
    void rejectsPaymentSnapshotWithoutVersion() {
        assertThatThrownBy(() -> service.project(event("PREPARED", 0))).isInstanceOf(IllegalArgumentException.class);

        verifyNoMoreInteractions(repository);
    }

    private PaymentProjectionEvent event(String status, long version) {
        Instant time = Instant.parse("2026-09-16T09:00:00Z");
        return new PaymentProjectionEvent("payment-event-1", "O-100", "P-100", status, version, time, time, "trace-1");
    }
}
