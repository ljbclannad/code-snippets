package com.lx.governance.outbox.config;

import javax.sql.DataSource;

import com.lx.governance.outbox.repo.ProductOutboxRepo;
import com.lx.governance.outbox.service.ProductOutboxPublisher;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "product-outbox.enabled", havingValue = "true")
public class ProductOutboxConfiguration {
    @Bean(name = "productOutboxDataSource")
    public DataSource productOutboxDataSource(
            @Value("${product-outbox.datasource.url}") String url,
            @Value("${product-outbox.datasource.username}") String username,
            @Value("${product-outbox.datasource.password}") String password) {
        HikariDataSource source = new HikariDataSource();
        source.setJdbcUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setMaximumPoolSize(2);
        return source;
    }

    @Bean
    public ProductOutboxRepo productOutboxRepo(@Qualifier("productOutboxDataSource") DataSource source) {
        return new ProductOutboxRepo(new JdbcTemplate(source));
    }

    @Bean
    public ProductOutboxPublisher productOutboxPublisher(ProductOutboxRepo repository,
            ConnectionFactory connectionFactory, MeterRegistry meters,
            @Value("${outbox.max-retries:5}") int maxRetries,
            @Value("${outbox.retry-delay-seconds:10}") int retryDelaySeconds) {
        // 使用独立模板，避免改变订单和支付发布器的默认模板行为。
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        return new ProductOutboxPublisher(repository, template, maxRetries, retryDelaySeconds, meters);
    }

    @Bean
    public Declarables productSearchQueues() {
        TopicExchange exchange = new TopicExchange("biz.events", true, false);
        DirectExchange deadExchange = new DirectExchange("biz.product.search.dead", true, false);
        Queue queue = QueueBuilder.durable("q.product.search")
                .deadLetterExchange("biz.product.search.dead").deadLetterRoutingKey("product.changed").build();
        Queue dead = QueueBuilder.durable("q.product.search.dead").build();
        return new Declarables(exchange, deadExchange, queue, dead,
                BindingBuilder.bind(queue).to(exchange).with("product.changed"),
                BindingBuilder.bind(dead).to(deadExchange).with("product.changed"));
    }
}
