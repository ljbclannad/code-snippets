# MCP Weather Demo

这个示例在同一个 Spring Boot 模块里提供两个运行 profile：

- `mcp-server`：启动一个 MCP Server，通过 SSE 暴露 `get_weather` 天气工具。工具使用 Spring AI MCP 注解 `@McpTool` 和 `@McpToolParam` 声明。
- `mcp-client`：启动一个 MCP Client，连接 server，并把远端 MCP tool 注册给 `ChatClient` 使用。

## 1. 启动 MCP Server

在第一个终端执行：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mcp-server
```

Server 默认端口是 `8082`，MCP SSE endpoint 是：

```text
http://localhost:8082/sse
```

## 2. 启动 MCP Client

在第二个终端执行：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mcp-client
```

Client 默认端口是 `8081`，它会连接 `http://localhost:8082/sse`。

## 3. 查看远端 MCP 工具

```bash
curl "http://localhost:8081/ai/mcp/tools"
```

预期可以看到 `get_weather`。

## 4. 查询天气

```bash
curl "http://localhost:8081/ai/mcp/weather?city=杭州"
```

流程是：

1. 浏览器或 curl 请求 MCP Client。
2. `WeatherMcpClientController` 把从 MCP Server 发现的 tool callbacks 交给 `ChatClient`。
3. 模型判断需要查天气，调用远端 MCP Server 的 `get_weather` 工具。
4. Client 用工具结果生成中文回答。

当前天气数据是本地 demo mock 数据，没有调用真实天气 API。
