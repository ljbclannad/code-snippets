package com.lx.governance.user.config;

import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;

@Configuration
public class ShardingSphereConfig {

    @Bean(name = "dataSource")
    @Primary
    public DataSource dataSource() throws SQLException, IOException {
        return YamlShardingSphereDataSourceFactory.createDataSource(
            new ClassPathResource("sharding-config.yaml").getFile()
        );
    }
}
