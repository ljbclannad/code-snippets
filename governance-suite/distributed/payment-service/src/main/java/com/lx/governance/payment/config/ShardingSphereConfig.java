package com.lx.governance.payment.config;

import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.InputStream;

@Configuration
public class ShardingSphereConfig {

    @Bean
    @Primary
    public DataSource shardingSphereDataSource() throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        InputStream inputStream = classLoader.getResourceAsStream("sharding-config.yaml");
        if (inputStream == null) {
            throw new IllegalStateException("sharding-config.yaml not found in classpath");
        }
        byte[] bytes = inputStream.readAllBytes();
        return YamlShardingSphereDataSourceFactory.createDataSource(bytes);
    }
}
