package com.lx.governance.payment.rebuild;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class PaymentRebuildDataSourceConfig {
    @Bean(name = "paymentRebuildDataSource")
    public DataSource paymentRebuildDataSource(@Value("${MYSQL_HOST:localhost}") String host,
            @Value("${MYSQL_PORT:3306}") int port, @Value("${MYSQL_USER:root}") String user,
            @Value("${MYSQL_PASS:root}") String password) {
        HikariDataSource source = new HikariDataSource();
        source.setJdbcUrl("jdbc:mysql://%s:%d/payment_db?useSSL=false&serverTimezone=Asia/Shanghai".formatted(host, port));
        source.setUsername(user);
        source.setPassword(password);
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setMaximumPoolSize(2);
        source.setReadOnly(true);
        return source;
    }

    @Bean(name = "paymentRebuildJdbcTemplate")
    public JdbcTemplate paymentRebuildJdbcTemplate(@Qualifier("paymentRebuildDataSource") DataSource source) {
        return new JdbcTemplate(source);
    }
}
