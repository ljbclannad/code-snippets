package com.lx.governance.payment.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lx.governance.common.id.IdSegmentRepository;
import com.zaxxer.hikari.HikariDataSource;

/**
 * ID 号段数据源配置。
 * 为 payment-service 提供访问 order_db 中 t_id_segment 表的数据源。
 */
@Configuration
@ConditionalOnProperty(name = "id.mode", havingValue = "segment", matchIfMissing = true)
public class IdSegmentDataSourceConfig {

    @Bean(name = "idSegmentDataSource")
    public DataSource idSegmentDataSource(
            @Value("${MYSQL_HOST:localhost}") String host,
            @Value("${MYSQL_PORT:3306}") int port,
            @Value("${MYSQL_USER:root}") String username,
            @Value("${MYSQL_PASS:root}") String password) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/payment_db?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                host, port));
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setMaximumPoolSize(5);
        dataSource.setMinimumIdle(1);
        dataSource.setConnectionTimeout(30000);
        dataSource.setPoolName("IdSegmentPool");
        return dataSource;
    }

    @Bean(name = "idSegmentJdbcTemplate")
    @Primary
    public JdbcTemplate idSegmentJdbcTemplate(DataSource idSegmentDataSource) {
        return new JdbcTemplate(idSegmentDataSource);
    }

    @Bean
    public IdSegmentRepository idSegmentRepository(JdbcTemplate idSegmentJdbcTemplate) {
        return new IdSegmentRepository(idSegmentJdbcTemplate);
    }
}
