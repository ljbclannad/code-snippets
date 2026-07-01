package io.github.messageguard.api.spi;

import io.github.messageguard.api.annotation.AlertLevel;

public interface AlertPublisher {

    void publish(AlertLevel level, String title, String message);
}
