package com.health.agent;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.health.agent.learning.mapper.AgentRunOutcomeMapper;
import com.health.agent.learning.service.AgentOutcomeRecorder;
import com.health.agent.learning.service.DefaultAgentOutcomeAttributionService;
import com.health.agent.learning.service.DefaultAgentOutcomeRecorder;
import com.health.agent.learning.service.DefaultAgentRunStrategySnapshotRecorder;
import com.health.agent.mapper.AgentSessionMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScannerRegistrar;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.type.AnnotationMetadata;

import static org.assertj.core.api.Assertions.assertThat;

class DoctorAssistantMapperScanTest {

    @Test
    void applicationMapperScanKeepsServiceInterfacesOutOfMybatis() {
        try (var context = new AnnotationConfigApplicationContext()) {
            new MapperScannerRegistrar().registerBeanDefinitions(
                    AnnotationMetadata.introspect(DoctorAssistantAgentApplication.class), context);
            var dataSource = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:mapper-scan", "sa", "");
            var configuration = new MybatisConfiguration(new Environment("test", new JdbcTransactionFactory(), dataSource));
            context.registerBean(SqlSessionFactory.class, () -> new DefaultSqlSessionFactory(configuration));
            context.register(DefaultAgentOutcomeRecorder.class, DefaultAgentRunStrategySnapshotRecorder.class,
                    DefaultAgentOutcomeAttributionService.class);

            context.refresh();

            assertThat(context.getBean(AgentOutcomeRecorder.class)).isInstanceOf(DefaultAgentOutcomeRecorder.class);
            assertThat(context.getBean(DefaultAgentOutcomeAttributionService.class)).isNotNull();
            assertThat(context.getBean(AgentSessionMapper.class)).isNotNull();
            assertThat(context.getBean(AgentRunOutcomeMapper.class)).isNotNull();
        }
    }
}
