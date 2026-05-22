package com.lx.governance.order.config;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.readwritesplitting.api.ReadwriteSplittingRuleConfiguration;
import org.apache.shardingsphere.readwritesplitting.api.rule.ReadwriteSplittingDataSourceRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.zaxxer.hikari.HikariDataSource;

@Configuration
public class ShardingSphereDataSourceConfig {

    @Value("${MYSQL_HOST:localhost}")
    private String mysqlHost;

    @Value("${MYSQL_USER:root}")
    private String mysqlUser;

    @Value("${MYSQL_PASS:root}")
    private String mysqlPass;

    @Bean("shardingSphereDataSource")
    @Primary
    public DataSource shardingSphereDataSource() throws SQLException {
        // Create actual data sources
        Map<String, DataSource> dataSourceMap = new HashMap<>();
        dataSourceMap.put("master", createDataSource(mysqlHost, 3306));
        dataSourceMap.put("slave1", createDataSource(mysqlHost, 3307));
        dataSourceMap.put("slave2", createDataSource(mysqlHost, 3308));

        // Read-write splitting rule
        ReadwriteSplittingDataSourceRuleConfiguration readwriteSplittingGroup = new ReadwriteSplittingDataSourceRuleConfiguration(
                "order_ds",
                "master",
                Arrays.asList("slave1", "slave2"),
                "round_robin");

        Map<String, AlgorithmConfiguration> loadBalancers = new HashMap<>();
        loadBalancers.put("round_robin", new AlgorithmConfiguration("ROUND_ROBIN", new Properties()));

        ReadwriteSplittingRuleConfiguration readwriteSplittingRule = new ReadwriteSplittingRuleConfiguration(
                Collections.singletonList(readwriteSplittingGroup),
                loadBalancers);

        // Sharding rule for t_order
        ShardingTableRuleConfiguration orderTableRule = new ShardingTableRuleConfiguration(
                "t_order",
                "order_ds.t_order_${0..1}");
        orderTableRule.setTableShardingStrategy(
                new StandardShardingStrategyConfiguration("order_no", "order-inline"));

        Map<String, AlgorithmConfiguration> shardingAlgorithms = new HashMap<>();
        Properties inlineProps = new Properties();
        inlineProps.setProperty("algorithm-expression", "t_order_${Math.abs(order_no.hashCode()) % 2}");
        shardingAlgorithms.put("order-inline", new AlgorithmConfiguration("INLINE", inlineProps));

        ShardingRuleConfiguration shardingRule = new ShardingRuleConfiguration();
        shardingRule.getTables().add(orderTableRule);
        shardingRule.setShardingAlgorithms(shardingAlgorithms);

        // SQL show
        Properties props = new Properties();
        props.setProperty("sql-show", "true");

        // Create ShardingSphere DataSource
        return ShardingSphereDataSourceFactory.createDataSource(
                dataSourceMap,
                Arrays.asList(readwriteSplittingRule, shardingRule),
                props);
    }

    private DataSource createDataSource(String host, int port) {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/order_db?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                host, port));
        ds.setUsername(mysqlUser);
        ds.setPassword(mysqlPass);
        ds.setMaximumPoolSize(port == 3306 ? 20 : 50);
        return ds;
    }
}
