# Spring AI 演示项目

## 项目简介

Spring AI 演示项目是一个全面展示 Spring AI 框架能力的示例应用，涵盖了从基础对话到高级功能（如工具调用、对话记忆、MCP 协议集成）的完整实践。本项目旨在帮助开发者快速理解和掌握 Spring AI 在实际应用中的使用方法。

## 技术栈

- **Java**: 17
- **Spring Boot**: 3.4.0
- **Spring AI**: 1.1.2
- **大模型**: DeepSeek Chat Model
- **数据库**: H2 内存数据库
- **协议**: MCP (Model Context Protocol)

## 核心功能

### 1. 基础对话 (Chat)
- 使用 `ChatClient` 与 DeepSeek 模型进行对话
- 支持流式和非流式响应
- 演示基本的问答交互模式

**入口**: `/ai/chat/**`

### 2. 提示词工程 (Prompt)
- System Prompt 和 User Prompt 的组合使用
- 提示词模板化管理
- 动态参数注入

**入口**: `/ai/prompt/**`

### 3. 结构化输出 (Structured Output)
- 将模型输出映射为 Java 对象
- 支持复杂数据结构解析
- 类型安全的响应处理

**入口**: `/ai/structured/**`

### 4. 工具调用 (Tool Calling)
- **注解式工具**: 使用注解声明工具函数
- **函数式工具**: 函数式编程风格的工具定义
- **工具上下文**: 工具间的上下文传递
- **直接返回**: 工具执行结果的直接返回

**入口**: `/ai/tool/**`

**示例**:
```bash
curl "http://localhost:8081/ai/tool/weather?city=杭州"
```

### 5. 对话记忆 (Chat Memory)
- 基于 JDBC 的对话历史持久化
- 支持多轮对话上下文管理
- 使用 H2 内存数据库存储会话

**入口**: `/ai/memory/**`

### 6. MCP 协议集成 (Model Context Protocol)
- **MCP Server**: 暴露本地工具为 MCP 服务
- **MCP Client**: 连接远程 MCP 服务并调用工具
- **天气查询示例**: 完整的 MCP 工作流演示

**入口**: `/ai/mcp/**`

详细说明请参考: [MCP_WEATHER_README.md](demo/MCP_WEATHER_README.md)

## 快速开始

### 前置条件

- JDK 17 或更高版本
- Maven 3.6+
- DeepSeek API Key

### 环境配置

在 `src/main/resources/application.yml` 中配置 API Key:

```yaml
spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
```

或通过环境变量设置:

```bash
export DEEPSEEK_API_KEY="your-api-key-here"
```

### 启动应用

#### 1. 标准模式（基础功能）

```bash
cd spring-ai/demo
mvn spring-boot:run
```

应用将在 `http://localhost:8081` 启动。

#### 2. MCP Server 模式

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mcp-server
```

MCP Server 将在 `http://localhost:8082` 启动。

#### 3. MCP Client 模式

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mcp-client
```

MCP Client 将在 `http://localhost:8081` 启动，并连接到 `http://localhost:8082/sse`。

## API 示例

### 基础对话

```bash
curl "http://localhost:8081/ai/chat/simple?message=你好"
```

### 工具调用

```bash
curl "http://localhost:8081/ai/tool/weather?city=北京"
```

### 结构化输出

```bash
curl -X POST "http://localhost:8081/ai/structured/parse" \
  -H "Content-Type: application/json" \
  -d '{"text": "我叫张三，今年25岁"}'
```

### MCP 工具发现

```bash
curl "http://localhost:8081/ai/mcp/tools"
```

### MCP 天气查询

```bash
curl "http://localhost:8081/ai/mcp/weather?city=上海"
```

## 项目结构

```
spring-ai/
└── demo/
    ├── src/main/java/com/example/
    │   ├── Application.java              # 主启动类
    │   ├── chat/                         # 基础对话
    │   │   └── ChatBasicController.java
    │   ├── prompts/                      # 提示词示例
    │   │   └── PromptBasicController.java
    │   ├── structured/                   # 结构化输出
    │   │   └── StructuredOutputController.java
    │   ├── tool/                         # 工具调用
    │   │   ├── ToolController.java
    │   │   └── tools/DateTimeTools.java
    │   ├── memory/                       # 对话记忆
    │   │   └── JdbcChatMemoryTestController.java
    │   ├── mcp/                          # MCP 集成
    │   │   ├── WeatherMcpServerConfig.java
    │   │   └── WeatherMcpClientController.java
    │   └── config/                       # 配置类
    │       └── ChatConfig.java
    └── src/main/resources/
        └── application.yml               # 应用配置
```

## 配置说明

### Profile 配置

项目支持三种运行模式，通过 Spring Profile 切换：

| Profile | 说明 | 端口 | 用途 |
|---------|------|------|------|
| default | 标准模式 | 8081 | 基础功能演示 |
| mcp-server | MCP 服务端 | 8082 | 暴露工具服务 |
| mcp-client | MCP 客户端 | 8081 | 调用远程工具 |

### 数据库配置

使用 H2 内存数据库存储对话历史：

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:chatdb
    driver-class-name: org.h2.Driver
  h2:
    console:
      enabled: true
      path: /h2-console
```

访问 H2 控制台: `http://localhost:8081/h2-console`

## 核心特性

### 1. 对话记忆持久化

使用 JDBC Repository 实现对话历史的持久化存储，支持：
- 多轮对话上下文保持
- 会话隔离
- 历史记录查询

### 2. 工具调用灵活性

支持多种工具定义方式：
- **注解式**: 简单直观，适合快速开发
- **函数式**: 灵活强大，适合复杂逻辑
- **上下文传递**: 工具间数据共享

### 3. MCP 协议支持

完整实现 MCP 协议的 Server 和 Client：
- Server 端暴露本地工具
- Client 端发现和调用远程工具
- 支持 SSE (Server-Sent Events) 通信

### 4. 结构化输出

将自然语言输出转换为结构化数据：
- 自动类型映射
- 字段验证
- 错误处理

## 开发指南

### 添加新的工具

1. 创建工具类并添加 `@Component` 注解
2. 定义工具方法并添加 `@Tool` 注解
3. 在 Controller 中注入并使用

示例：

```java
@Component
public class MyTools {
    
    @Tool(description = "获取当前时间")
    public String getCurrentTime() {
        return LocalDateTime.now().toString();
    }
}
```

### 自定义对话记忆

实现 `ChatMemory` 接口或继承现有实现：

```java
@Bean
public ChatMemory chatMemory(DataSource dataSource) {
    return new JdbcChatMemoryRepository(dataSource);
}
```

### 扩展 MCP 功能

在 `WeatherMcpServerConfig` 中注册新的工具：

```java
@Bean
public McpAsyncServer mcpServer() {
    return McpAsyncServer.builder()
        .tool("my-tool", "工具描述", this::myToolHandler)
        .build();
}
```

## 注意事项

1. **API Key 安全**: 不要将 API Key 硬编码在代码中，使用环境变量或配置文件管理
2. **天气数据**: 当前天气数据为 Mock 数据，未调用真实天气 API
3. **内存数据库**: H2 数据库为内存模式，应用重启后数据会丢失
4. **MCP 端口**: MCP Server 和 Client 需要使用不同端口，避免冲突

## 常见问题

### Q: 如何切换到其他大模型？

A: 修改 `application.yml` 中的模型配置，Spring AI 支持 OpenAI、Azure OpenAI、Anthropic 等多种模型。

### Q: 对话记忆如何持久化到 MySQL？

A: 修改 `datasource` 配置，将 H2 替换为 MySQL，并确保表结构正确。

### Q: MCP Server 和 Client 必须分开启动吗？

A: 是的，MCP 协议基于客户端-服务端架构，需要分别启动。也可以部署在不同机器上。

## 相关资源

- [Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/)
- [DeepSeek API 文档](https://platform.deepseek.com/docs)
- [MCP 协议规范](https://modelcontextprotocol.io/)

## 许可证

本项目仅供学习和演示使用。
