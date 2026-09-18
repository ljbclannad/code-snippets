package com.lx.governance.outbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.lx.governance.outbox.service.ProductOutboxPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductOutboxConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(ProductOutboxConfiguration.class)
            .withBean(ConnectionFactory.class, CachingConnectionFactory::new)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void disabledByDefaultDoesNotCreateProductDatasourceOrPublisher() {
        context.run(app -> {
            assertThat(app).doesNotHaveBean("productOutboxDataSource");
            assertThat(app).doesNotHaveBean(ProductOutboxPublisher.class);
        });
    }

    @Test
    void enabledDeclaresDurableBacklogAndDeadLetterQueuesWithoutReplacingDefaultTemplate() {
        context.withPropertyValues("product-outbox.enabled=true",
                "product-outbox.datasource.url=jdbc:mysql://localhost:3306/product_db",
                "product-outbox.datasource.username=root", "product-outbox.datasource.password=test")
                .run(app -> {
                    assertThat(app).hasSingleBean(ProductOutboxPublisher.class);
                    assertThat(app).doesNotHaveBean(RabbitTemplate.class);
                    assertThat(app.getBean(Declarables.class).getDeclarablesByType(Queue.class))
                            .anySatisfy(queue -> {
                                assertThat(queue.getName()).isEqualTo("q.product.search");
                                assertThat(queue.isDurable()).isTrue();
                                assertThat(queue.getArguments()).containsEntry("x-dead-letter-exchange", "biz.product.search.dead")
                                        .containsEntry("x-dead-letter-routing-key", "product.changed");
                            }).anySatisfy(queue -> {
                                assertThat(queue.getName()).isEqualTo("q.product.search.dead");
                                assertThat(queue.isDurable()).isTrue();
                            });
                });
    }
}
