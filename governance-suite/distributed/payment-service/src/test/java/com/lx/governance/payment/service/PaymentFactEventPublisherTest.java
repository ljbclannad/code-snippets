package com.lx.governance.payment.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.payment.domain.PaymentDto;
import com.lx.governance.payment.repo.PaymentOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentFactEventPublisherTest {
    @Mock
    private PaymentOutboxRepository repository;

    @Test
    void rejectsPaymentSnapshotWithoutProjectionVersion() {
        PaymentFactEventPublisher publisher = new PaymentFactEventPublisher(repository, new ObjectMapper().findAndRegisterModules());
        PaymentDto payment = new PaymentDto();
        payment.setOrderNo("O-100");

        assertThatThrownBy(() -> publisher.publish(payment)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesVersionedPaymentStatusEventToOutbox() {
        PaymentFactEventPublisher publisher = new PaymentFactEventPublisher(repository, new ObjectMapper().findAndRegisterModules());
        PaymentDto payment = new PaymentDto();
        payment.setOrderNo("O-100");
        payment.setPayNo("P-100");
        payment.setStatus("SUCCEEDED");
        payment.setVersion(2L);
        payment.setUpdatedAt(Instant.parse("2026-09-16T09:00:00Z"));

        publisher.publish(payment);

        verify(repository).insert(anyString(), org.mockito.ArgumentMatchers.eq("O-100"),
                org.mockito.ArgumentMatchers.eq("payment.status.changed"), org.mockito.ArgumentMatchers.contains("SUCCEEDED"));
    }
}
