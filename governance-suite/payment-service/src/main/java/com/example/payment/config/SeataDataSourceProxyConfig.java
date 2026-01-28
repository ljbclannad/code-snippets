package com.example.payment.config;

import io.seata.rm.datasource.DataSourceProxy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class SeataDataSourceProxyConfig {
    @Bean(name = "dataSource")
    @Primary
    public DataSource dataSource(@Qualifier("shardingSphereDataSource") DataSource shardingDs) {
        return new DataSourceProxy(shardingDs);
    }
}
