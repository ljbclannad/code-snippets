package com.lx.governance.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lx.governance.outbox.repo.ProductOutboxRepo;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ProductOutboxPublisherTest {
    private JdbcTemplate jdbc;
    private RabbitTemplate rabbit;
    private ProductOutboxPublisher publisher;
    private final List<Message> published = new ArrayList<>();

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE t_product_outbox (id BIGINT PRIMARY KEY, event_id VARCHAR(64), event_type VARCHAR(64), payload CLOB, status VARCHAR(16), retry_count INT DEFAULT 0, next_retry_at TIMESTAMP, error_reason VARCHAR(500), sent_at TIMESTAMP)");
        jdbc.update("INSERT INTO t_product_outbox(id,event_id,event_type,payload,status) VALUES (1,'event-1','product.changed','{\"eventId\":\"event-1\"}','NEW')");
        rabbit = mock(RabbitTemplate.class);
        publisher = new ProductOutboxPublisher(new ProductOutboxRepo(jdbc), rabbit, 2, 60, new SimpleMeterRegistry());
    }

    @Test
    void confirmedEventsArePublishedOnceAcrossPolls() {
        doAnswer(invocation -> {
            published.add(invocation.getArgument(2));
            invocation.<CorrelationData>getArgument(3).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(eq("biz.events"), eq("product.changed"), any(Message.class), any(CorrelationData.class));
        publisher.pollAndPublish();
        publisher.pollAndPublish();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).getMessageProperties().getMessageId()).isEqualTo("event-1");
        assertThat(published.get(0).getBody()).asString().isEqualTo("{\"eventId\":\"event-1\"}");
    }
    @Test
    void unroutableAckIsRetriedWithSameEventAndStopsAfterRetryBudget() {
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            published.add(message);
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "biz.events", "product.changed"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(eq("biz.events"), eq("product.changed"), any(Message.class), any(CorrelationData.class));
        publisher.pollAndPublish();
        publisher.pollAndPublish();
        assertThat(published).hasSize(1);
        jdbc.update("UPDATE t_product_outbox SET next_retry_at=NULL WHERE id=1");
        publisher.pollAndPublish();
        publisher.pollAndPublish();
        assertThat(published).hasSize(2);
        assertThat(published).allSatisfy(message -> assertThat(message.getMessageProperties().getMessageId()).isEqualTo("event-1"));
        assertThat(jdbc.queryForObject("SELECT status FROM t_product_outbox WHERE id=1", String.class)).isEqualTo("DEAD");
    }
}
