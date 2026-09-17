package com.lx.governance.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import java.nio.charset.StandardCharsets;

import com.lx.governance.order.observability.AdminOrderActivity;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectionEventRecoveryTest {
    @Test
    void forwardsTransientFailureToRetryQueueBeforeAck() throws Exception {
        RabbitTemplate rabbit = confirmedRabbit();
        Channel channel = mock(Channel.class);
        AdminOrderActivity activity = mock(AdminOrderActivity.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ProjectionEventRecovery recovery = new ProjectionEventRecovery(rabbit, meters, activity, 2);

        recovery.failed("payment", "retry", "dead", message(0), channel, false, new IllegalStateException());

        ArgumentCaptor<Message> forwarded = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq(""), eq("retry"), forwarded.capture(), any(CorrelationData.class));
        assertThat(forwarded.getValue().getMessageProperties().getHeaders().get("projection-retry-count")).isEqualTo(1);
        verify(channel).basicAck(42L, false);
        assertThat(meters.counter("admin.order.projection.events.total", "source", "payment", "outcome", "retry").count())
                .isEqualTo(1);
    }

    @Test
    void sendsExhaustedFailureToDeadQueue() throws Exception {
        RabbitTemplate rabbit = confirmedRabbit();
        Channel channel = mock(Channel.class);
        ProjectionEventRecovery recovery = new ProjectionEventRecovery(rabbit, new SimpleMeterRegistry(),
                mock(AdminOrderActivity.class), 2);

        recovery.failed("order", "retry", "dead", message(2), channel, false, new IllegalStateException());

        verify(rabbit).send(eq(""), eq("dead"), any(Message.class), any(CorrelationData.class));
        verify(channel).basicAck(42L, false);
    }

    @Test
    void requeuesOriginalWhenForwardingCannotBeConfirmed() throws Exception {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        Channel channel = mock(Channel.class);
        ProjectionEventRecovery recovery = new ProjectionEventRecovery(rabbit, new SimpleMeterRegistry(),
                mock(AdminOrderActivity.class), 2);

        assertThatThrownBy(() -> recovery.failed("order", "retry", "dead", message(0), channel, false,
                new IllegalStateException())).isInstanceOf(IllegalStateException.class);

        verify(channel).basicNack(42L, false, true);
        verify(channel, never()).basicAck(42L, false);
    }

    private RabbitTemplate confirmedRabbit() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(eq(""), any(String.class), any(Message.class), any(CorrelationData.class));
        return rabbit;
    }

    private Message message(int retries) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("event-1");
        properties.setDeliveryTag(42L);
        properties.setHeader("projection-retry-count", retries);
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }
}
