# Dify API Test (Java)

用于测试 Dify API 的最小 Java 示例，覆盖 workflow 与 agent 两类调用。

## 依赖与前置条件

- JDK 11+
- Maven 3.6+
- 本地 Dify 服务可访问：`http://localhost/`
- 需要 Dify 应用的 API Key（按应用类型选择：workflow 或 agent）

## 运行方式

在项目目录下执行：

```bash
cd dify-api-test-java
mvn -q -DskipTests package
```

### 测试 Workflow

```bash
export DIFY_API_KEY="你的workflow应用API Key"
export DIFY_BASE_URL="http://localhost"
java -cp target/classes com.example.dify.WorkflowTest
```

可选传入 `inputs` JSON：

```bash
java -cp target/classes com.example.dify.WorkflowTest '{"text":"hello"}'
```

### 测试 Agent

```bash
export DIFY_API_KEY="你的agent应用API Key"
export DIFY_BASE_URL="http://localhost"
java -cp target/classes com.example.dify.AgentTest
```

可选传入 `query` 与 `inputs` JSON：

```bash
java -cp target/classes com.example.dify.AgentTest "hello from java" '{"topic":"demo"}'
```

## 可配置环境变量

- `DIFY_API_KEY`：必填，Dify 应用 API Key
- `DIFY_BASE_URL`：可选，默认 `http://localhost`
- `DIFY_USER`：可选，默认 `java-test-user`
- `DIFY_CONVERSATION_ID`：可选，仅 agent 测试时使用
- `DIFY_TRACE_ID`：可选，链路追踪 ID，优先通过 Header `X-Trace-Id` 传递，示例会同时写入 body 的 `trace_id`
