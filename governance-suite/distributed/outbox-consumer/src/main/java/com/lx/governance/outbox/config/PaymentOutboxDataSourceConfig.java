package com.lx.governance.outbox.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class PaymentOutboxDataSourceConfig {
    @Bean(name = "paymentOutboxJdbcTemplate")
    public JdbcTemplate paymentOutboxJdbcTemplate(
            @Value("${payment-outbox.datasource.url}") String url,
            @Value("${payment-outbox.datasource.username}") String username,
            @Value("${payment-outbox.datasource.password}") String password) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return new JdbcTemplate(dataSource);
    }
}
