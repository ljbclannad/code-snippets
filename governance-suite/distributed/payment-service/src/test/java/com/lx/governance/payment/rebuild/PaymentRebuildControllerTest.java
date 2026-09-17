package com.lx.governance.payment.rebuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

class PaymentRebuildControllerTest {
    @Test
    void boundsPageAndRejectsInvalidShard() {
        PaymentRebuildRepository repository = mock(PaymentRebuildRepository.class);
        PaymentRebuildController controller = new PaymentRebuildController(repository);
        when(repository.page(1, 10, 20, 100)).thenReturn(List.of());

        assertThat(controller.page(1, 10, 20, 100).getData().items()).isEmpty();
        verify(repository).page(1, 10, 20, 100);
        assertThatThrownBy(() -> controller.page(0, 0, 20, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.page(2, 0, 20, 100)).isInstanceOf(IllegalArgumentException.class);
    }
}
