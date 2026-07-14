# AI 服务测试分层

默认命令仅执行不依赖外部基础设施的单元测试、契约测试和离线评测：

```bash
mvn -f ../pom.xml -pl ai/ai-chat-service,ai/doctor-assistant-agent,ai/knowledge-base-service,ai/vector-memory-service,ai/user-profile-service -am test
```

需要 Redis、MySQL、Milvus、RabbitMQ、Elasticsearch 或真实模型服务的测试使用
`@EnabledIfSystemProperty(named = "ai.external.tests", matches = "true")` 标记；只有显式开启时才会运行：

```bash
mvn -f ../pom.xml -pl ai/ai-chat-service,ai/knowledge-base-service,ai/vector-memory-service,ai/user-profile-service -am test -Dai.external.tests=true
```

这两条通道的责任不同：默认门禁追求可重复和快速反馈；外部测试验证真实基础设施集成，必须在已准备独立测试环境时运行。

外部测试统一启用 `external-test` profile。该 profile 不提供开发环境默认值，必须显式传入以 `AI_TEST_` 开头的连接参数（例如 `AI_TEST_DB_URL`、`AI_TEST_REDIS_HOST`、`AI_TEST_MILVUS_HOST`）。因此，缺少独立测试环境配置会在启动前失败，而不会误连 `localhost/governance` 开发数据。完整参数见各服务的 `src/test/resources/application-external-test.yml`。

## 患者对话评测集

`ai-chat-service/src/test/resources/evaluation/patient-chat-v1.json` 是首版真实问法基线。每条样本包含：

- 原始患者表达、会话复杂度、上游意图和待补全问题；
- 期望路由、任务类型和安全原因；
- 未来模型回答评测用的必含/禁含语义信号。

`PatientChatRoutingEvaluationTest` 在默认门禁中执行路由和安全断言。当前它只验证确定性的编排层；真实模型接入时，应复用同一数据集评估证据覆盖、拒答正确性和回答质量，不能以字面文本完全相等作为判定标准。
