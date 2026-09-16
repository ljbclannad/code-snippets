package com.lx.governance.order.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class OrderAdminReadModelDataSourceConfig {
    @Value("${MYSQL_HOST:localhost}")
    private String host;

    @Value("${MYSQL_PORT:3306}")
    private String port;

    @Value("${MYSQL_USER:root}")
    private String username;

    @Value("${MYSQL_PASS:root}")
    private String password;

    @Bean(name = "orderAdminReadModelDataSource")
    public DataSource orderAdminReadModelDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(String.format("jdbc:mysql://%s:%s/order_db?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useAffectedRows=true", host, port));
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setMaximumPoolSize(4);
        dataSource.setMinimumIdle(1);
        return dataSource;
    }

    @Bean(name = "orderAdminReadModelJdbcTemplate")
    public JdbcTemplate orderAdminReadModelJdbcTemplate() {
        return new JdbcTemplate(orderAdminReadModelDataSource());
    }
}
