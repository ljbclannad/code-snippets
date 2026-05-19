package com.health.agent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 配置
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("医生助手 ReAct Agent API")
                        .version("1.0.0")
                        .description("""
                                # 医生助手 ReAct Agent 系统

                                基于 ReAct (Reasoning + Acting) 架构的智能医生助手系统，支持自主推理和多步决策。

                                ## 核心功能

                                - **自主推理**：Agent 自动分析患者需求，制定执行计划
                                - **多步决策**：支持复杂的多步骤任务执行
                                - **风险分级**：三级风险控制（低/中/高），高风险操作需医生确认
                                - **状态持久化**：支持会话暂停和恢复
                                - **向量检索**：基于 Milvus 的相似病例检索
                                - **实时监控**：完整的监控指标体系

                                ## 工具能力

                                - 查询患者档案、体重历史、聊天记录、处方历史
                                - 搜索相似病例、分析健康趋势、情感分析、风险评分
                                - 发送健康提醒、创建随访任务、标记高风险患者
                                - 起草处方建议、诊断建议（需医生确认）

                                ## 使用流程

                                1. **启动 Agent**：POST /api/agent/execute，提供患者 ID、医生 ID 和目标
                                2. **等待执行**：Agent 自动执行，遇到高风险操作会暂停
                                3. **审批任务**：GET /api/agent/tasks/pending 查看待审批任务
                                4. **确认/拒绝**：POST /api/agent/tasks/{taskId}/approve 或 reject
                                5. **恢复执行**：POST /api/agent/resume，Agent 继续执行
                                6. **查看结果**：GET /api/agent/sessions/{sessionId} 查看完整执行历史
                                """)
                        .contact(new Contact()
                                .name("Health AI Team")
                                .email("support@health-ai.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0.html")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8088")
                                .description("本地开发环境"),
                        new Server()
                                .url("http://localhost:8088")
                                .description("Docker 环境")
                ));
    }
}
