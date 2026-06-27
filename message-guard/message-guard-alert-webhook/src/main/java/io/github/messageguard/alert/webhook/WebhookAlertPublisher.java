package io.github.messageguard.alert.webhook;

import io.github.messageguard.api.annotation.AlertLevel;
import io.github.messageguard.api.spi.AlertPublisher;
import org.springframework.web.client.RestClient;

public class WebhookAlertPublisher implements AlertPublisher {

    private final RestClient restClient;
    private final String webhookUrl;

    public WebhookAlertPublisher(RestClient restClient, String webhookUrl) {
        this.restClient = restClient;
        this.webhookUrl = webhookUrl;
    }

    @Override
    public void publish(AlertLevel level, String title, String message) {
        restClient.post()
                .uri(webhookUrl)
                .body(new AlertPayload(level.name(), title, message))
                .retrieve()
                .toBodilessEntity();
    }

    record AlertPayload(String level, String title, String message) {
    }
}
