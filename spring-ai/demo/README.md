# Spring AI Demo

这个模块是一个 Spring AI demo 项目，用来演示在 Spring Boot 中集成大模型对话、提示词、结构化输出、工具调用、对话记忆和 MCP。

## 技术栈

- Java 17
- Spring Boot 3.4.0
- Spring AI 1.1.2
- DeepSeek Chat Model
- H2 内存数据库
- Spring AI MCP Server / MCP Client

## 示例内容

| 功能 | 说明 | 入口 |
| --- | --- | --- |
| 基础对话 | 使用 `ChatClient` 调用 DeepSeek 模型 | `/ai/chat/**` |
| Prompt 示例 | 演示 system/user prompt 的基础用法 | `/ai/prompt/**` |
| 结构化输出 | 演示模型输出映射为结构化 Java 对象 | `/ai/structured/**` |
| Tool Calling | 演示注解工具、函数式工具、工具上下文、直接返回 | `/ai/tool/**` |
| JDBC Chat Memory | 使用 JDBC repository 保存对话记忆 | `/ai/memory/**` |
| MCP 查询天气 | MCP Server 暴露天气工具，MCP Client 连接后供模型调用 | `/ai/mcp/**` |

## 快速启动

在当前模块目录执行：

```bash
mvn spring-boot:run
```

默认端口：

```text
http://localhost:8081
```

示例：

```bash
curl "http://localhost:8081/ai/tool/weather?city=杭州"
```

## MCP 天气示例

MCP 示例需要分别启动 server 和 client。

第一个终端启动 MCP Server：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mcp-server
```

第二个终端启动 MCP Client：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mcp-client
```

查看 MCP Client 发现的远端工具：

```bash
curl "http://localhost:8081/ai/mcp/tools"
```

查询天气：

```bash
curl "http://localhost:8081/ai/mcp/weather?city=杭州"
```

更完整的 MCP 说明见 [MCP_WEATHER_README.md](MCP_WEATHER_README.md)。

## 配置说明

主要配置位于：

```text
src/main/resources/application.yml
```

默认配置会启动普通 Spring AI demo，不启用 MCP server/client。MCP 通过 profile 区分：

- `mcp-server`：启用 MCP Server，端口 `8082`
- `mcp-client`：启用 MCP Client，端口 `8081`，连接 `http://localhost:8082/sse`

当前天气数据是 demo mock 数据，没有调用真实天气 API。
