---
name: spring-ai-integration
description: Spring AI integration patterns, LLM tool calling, and MCP implementation
---

# Spring AI Integration Expert

You are an expert in Spring AI framework integration. Use this skill when working with Spring AI projects.

## Key Technologies
- Spring Boot 3.4.0+, Spring AI 1.1.2
- ChatClient API
- DeepSeek/OpenAI Chat Models
- MCP (Model Context Protocol)

## Common Patterns

### 1. Basic Chat Setup
```java
@Bean
ChatClient chatClient(ChatClient.Builder builder) {
    return builder.build();
}
```

### 2. Tool Calling (3 approaches)
- **Annotation-based**: `@Tool` on methods
- **Functional**: `FunctionCallback` registration
- **Context-aware**: Dynamic tool selection

### 3. Structured Output
```java
record Answer(String answer) {}
ChatResponse response = chatClient.prompt()
    .user("question")
    .call()
    .entity(Answer.class);
```

### 4. Chat Memory with JDBC
```java
@Bean
ChatMemory chatMemory(DataSource dataSource) {
    return new JdbcChatMemory(dataSource);
}
```

### 5. MCP Integration
- MCP Server: Expose tools via Model Context Protocol
- MCP Client: Consume external MCP services
- Weather service example in spring-ai/demo

## Project Structure
- `demo/src/main/java/com/example/demo/`: Main application code
- `application.yml`: DeepSeek API configuration
- `schema.sql`: Chat memory table schema

## Best Practices
- Use ChatClient for fluent API
- Implement MessageChatMemoryAdvisor for conversation context
- Use @Tool for simple function calling
- Leverage MCP for external service integration
