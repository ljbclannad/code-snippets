package com.lx.governance.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import com.lx.governance.order.domain.OrderProjectionEvent;
import com.lx.governance.order.repo.OrderAdminReadModelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderAdminProjectionServiceTest {
    @Mock
    private OrderAdminReadModelRepository repository;

    @InjectMocks
    private OrderAdminProjectionService service;

    @Test
    void appliesCreatedOrderSnapshotToReadModel() {
        OrderProjectionEvent event = event("evt-created", "CREATED", 1L, 100, 2);
        when(repository.upsertOrderIfNewer(event)).thenReturn(1);

        boolean applied = service.project(event);

        assertThat(applied).isTrue();
        verify(repository).upsertOrderIfNewer(event);
    }

    @Test
    void keepsCancelledOrderAsHistoricalRecord() {
        OrderProjectionEvent event = event("evt-cancelled", "CANCELLED", 2L, 100, 2);
        when(repository.upsertOrderIfNewer(event)).thenReturn(1);

        boolean applied = service.project(event);

        assertThat(applied).isTrue();
        verify(repository).upsertOrderIfNewer(event);
    }

    @Test
    void skipsDuplicateOrOlderProjectionEvent() {
        OrderProjectionEvent event = event("evt-duplicate", "CREATED", 1L, 100, 2);
        when(repository.upsertOrderIfNewer(event)).thenReturn(0);

        boolean applied = service.project(event);

        assertThat(applied).isFalse();
        verify(repository).upsertOrderIfNewer(event);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rejectsInvalidEventBeforeWritingReadModel() {
        OrderProjectionEvent event = event("", "CREATED", 0L, 100, 2);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.project(event))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoMoreInteractions(repository);
    }

    private OrderProjectionEvent event(String eventId, String status, long version, int amountCents, int quantity) {
        return new OrderProjectionEvent(eventId, "O-100", "customer-1", "product-1", quantity, amountCents,
                status, version, Instant.parse("2026-09-14T10:00:00Z"), Instant.parse("2026-09-15T10:00:00Z"), "trace-1");
    }
}
