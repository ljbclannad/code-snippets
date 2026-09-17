package com.lx.governance.order.export;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import com.lx.governance.order.adminauth.AdminSearchScope;
import com.lx.governance.order.adminauth.AdminSearchScopeResolver;
import com.lx.governance.order.observability.AdminOrderActivity;
import com.lx.governance.order.repo.FinanceExportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FinanceExportServiceTest {
    private final Instant now = Instant.parse("2026-09-17T10:00:00Z");
    private AdminSearchScopeResolver scopeResolver;
    private FinanceExportRepository repository;
    private FinanceExportFileStorage storage;
    private FinanceExportService service;

    @BeforeEach
    void setUp() {
        scopeResolver = mock(AdminSearchScopeResolver.class);
        repository = mock(FinanceExportRepository.class);
        storage = mock(FinanceExportFileStorage.class);
        service = new FinanceExportService(scopeResolver, repository, mock(AdminOrderActivity.class), storage,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void downloadRechecksFinanceRoleBeforeReadingTaskOrFile() {
        when(scopeResolver.resolve("operator"))
                .thenReturn(new AdminSearchScope(1, "operator", Set.of("ORDER_OPERATOR")));

        assertThatThrownBy(() -> service.download("operator", "task"))
                .isInstanceOf(FinanceExportService.FinanceExportFinanceRoleRequiredException.class);
        verifyNoInteractions(repository, storage);
    }

    @Test
    void downloadHidesAnotherOwnersTask() {
        when(scopeResolver.resolve("finance"))
                .thenReturn(new AdminSearchScope(2, "finance", Set.of("FINANCE_VIEWER")));
        when(repository.find("task")).thenReturn(Optional.of(task(1, now.plusSeconds(60))));

        assertThatThrownBy(() -> service.download("finance", "task"))
                .isInstanceOf(FinanceExportService.FinanceExportNotFoundException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void downloadRejectsFileExactlyAtTwentyFourHourExpiry() {
        when(scopeResolver.resolve("finance"))
                .thenReturn(new AdminSearchScope(1, "finance", Set.of("FINANCE_VIEWER")));
        when(repository.find("task")).thenReturn(Optional.of(task(1, now)));

        assertThatThrownBy(() -> service.download("finance", "task"))
                .isInstanceOf(FinanceExportService.FinanceExportExpiredException.class);
        verifyNoInteractions(storage);
    }

    private FinanceExportTask task(long owner, Instant expiresAt) {
        return new FinanceExportTask("task", owner, FinanceExportStatus.READY, "fingerprint", null, null,
                null, null, now, null, null, 1, "task.csv", null, now.minusSeconds(86400),
                now.minusSeconds(86400), expiresAt);
    }
}
