package io.github.messageguard.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumeStatusTest {

    @Test
    void shouldTreatSuccessAndDeadAsTerminalStates() {
        assertThat(ConsumeStatus.SUCCESS.isTerminal()).isTrue();
        assertThat(ConsumeStatus.DEAD.isTerminal()).isTrue();
        assertThat(ConsumeStatus.PROCESSING.isTerminal()).isFalse();
    }
}
