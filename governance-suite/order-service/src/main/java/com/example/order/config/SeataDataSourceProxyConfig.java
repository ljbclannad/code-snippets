package com.example.order.config;

import io.seata.rm.datasource.DataSourceProxy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class SeataDataSourceProxyConfig {

    /**
     * ShardingSphere 会提供一个名为 shardingSphereDataSource 的 DataSource。
     * 我们把它包一层 Seata DataSourceProxy，交给 MyBatis/TransactionManager 使用。
     */
    @Bean(name = "dataSource")
    @Primary
    public DataSource dataSource(@Qualifier("shardingSphereDataSource") DataSource shardingDs) {
        return new DataSourceProxy(shardingDs);
    }
}
