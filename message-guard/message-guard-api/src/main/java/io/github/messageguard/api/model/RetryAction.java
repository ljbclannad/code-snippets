package io.github.messageguard.api.model;

public enum RetryAction {
    IMMEDIATE_RETRY,
    DELAY_RETRY,
    DEAD_LETTER,
    TRIGGER_COMPENSATION
}
