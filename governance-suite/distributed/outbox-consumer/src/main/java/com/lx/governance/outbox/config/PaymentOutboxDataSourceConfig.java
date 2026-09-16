package com.lx.governance.outbox.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class PaymentOutboxDataSourceConfig {
    @Bean(name = "orderOutboxDataSource")
    @Primary
    public DataSource orderOutboxDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return dataSource;
    }

    @Bean(name = "orderOutboxJdbcTemplate")
    public JdbcTemplate orderOutboxJdbcTemplate(
            @Qualifier("orderOutboxDataSource") DataSource orderOutboxDataSource) {
        return new JdbcTemplate(orderOutboxDataSource);
    }

    @Bean(name = "paymentOutboxDataSource")
    public DataSource paymentOutboxDataSource(
            @Value("${payment-outbox.datasource.url}") String url,
            @Value("${payment-outbox.datasource.username}") String username,
            @Value("${payment-outbox.datasource.password}") String password) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return dataSource;
    }

    @Bean(name = "paymentOutboxJdbcTemplate")
    public JdbcTemplate paymentOutboxJdbcTemplate(
            @Qualifier("paymentOutboxDataSource") DataSource paymentOutboxDataSource) {
        return new JdbcTemplate(paymentOutboxDataSource);
    }
}
