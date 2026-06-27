package io.github.messageguard.core.model;

public enum ConsumeStatus {
    PROCESSING(false),
    SUCCESS(true),
    RETRYING(false),
    FAILED(false),
    DEAD(true),
    COMPENSATING(false),
    COMPENSATED(true),
    COMPENSATION_FAILED(false),
    IGNORED(true);

    private final boolean terminal;

    ConsumeStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
