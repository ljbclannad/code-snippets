package com.lx.governance.order.config;

import com.lx.governance.common.id.IdSegmentRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class IdSegmentDataSourceConfig {

    @Value("${MYSQL_HOST:localhost}")
    private String host;

    @Value("${MYSQL_PORT:3306}")
    private String port;

    @Value("${MYSQL_USER:root}")
    private String username;

    @Value("${MYSQL_PASS:root}")
    private String password;

    @Bean(name = "idSegmentDataSource")
    public DataSource idSegmentDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(String.format(
            "jdbc:mysql://%s:%s/order_db?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
            host, port));
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setMaximumPoolSize(2);
        dataSource.setMinimumIdle(1);
        dataSource.setConnectionTimeout(30000);
        return dataSource;
    }

    @Bean
    public IdSegmentRepository idSegmentRepository() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(idSegmentDataSource());
        return new IdSegmentRepository(jdbcTemplate);
    }
}
