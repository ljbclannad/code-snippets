# AI 服务 RabbitMQ RPC 通信修复总结

## 📅 日期
2026-05-14

## ✅ 问题解决

### 核心问题
RabbitMQ RPC 响应接收超时 - ai-chat-service 无法接收来自 user-profile-service 和 vector-memory-service 的响应

### 根本原因
1. **响应消息缺少 correlationId header** - user-profile-service 和 vector-memory-service 发送响应时未设置 correlationId
2. **DirectReplyTo 机制配置复杂** - Spring AMQP 的动态响应队列机制需要复杂的配置

### 解决方案
**使用固定响应队列替代动态队列**

#### 1. 创建固定响应队列
```java
// RabbitMQConfig.java
public static final String USER_PROFILE_REPLY_QUEUE = "ai.chat.user.profile.reply";
public static final String VECTOR_MEMORY_REPLY_QUEUE = "ai.chat.vector.memory.reply";

@Bean
public Queue userProfileReplyQueue() {
    return new Queue("ai.chat.user.profile.reply", true);
}

@Bean
public Queue vectorMemoryReplyQueue() {
    return new Queue("ai.chat.vector.memory.reply", true);
}
```

#### 2. 实现固定队列客户端
```java
// UserProfileClientFixed.java
public class UserProfileClientFixed {
    private static final String REPLY_QUEUE = "ai.chat.user.profile.reply";
    private final Map<String, CompletableFuture<UserProfileResponseMessage>> pendingRequests = new ConcurrentHashMap<>();
    
    // 发送请求时指定固定 replyTo
    public CompletableFuture<UserProfileDTO> requestUserProfile(String customerId) {
        CompletableFuture<UserProfileResponseMessage> future = new CompletableFuture<>();
        pendingRequests.put(correlationId, future);
        
        rabbitTemplate.convertAndSend(REQUEST_QUEUE, request, message -> {
            message.getMessageProperties().setReplyTo(REPLY_QUEUE);
            message.getMessageProperties().setCorrelationId(correlationId);
            return message;
        });
        
        return future.orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)...
    }
    
    // 监听固定队列接收响应
    @RabbitListener(queues = REPLY_QUEUE)
    public void handleResponse(UserProfileResponseMessage response, Message message) {
        String correlationId = message.getMessageProperties().getCorrelationId();
        CompletableFuture<UserProfileResponseMessage> future = pendingRequests.remove(correlationId);
        if (future != null) {
            future.complete(response);
        }
    }
}
```

#### 3. 修复服务端响应消息
```java
// UserProfileMessageListener.java
rabbitTemplate.convertAndSend("", replyTo, response, msg -> {
    msg.getMessageProperties().setCorrelationId(correlationId);  // 关键：设置 correlationId
    return msg;
});
```

## 📊 测试结果

### ✅ 成功
- RabbitMQ RPC 通信正常
- user-profile-service 接收请求并发送响应 ✅
- ai-chat-service 成功接收响应 ✅
- 用户画像数据正常获取 ✅
- 向量记忆数据正常获取 ✅

### ❌ 待解决
- DeepSeek API 认证失败（需要有效的 API Key）

## 🔍 日志验证

### user-profile-service
```
[c091a5407d5d495291a154c0664a79b1] INFO - 收到用户画像请求: customerId=test-customer-001, replyTo=ai.chat.user.profile.reply
[c091a5407d5d495291a154c0664a79b1] INFO - 用户画像响应已发送到 replyTo 队列: ai.chat.user.profile.reply, success=true
```

### ai-chat-service
```
INFO - Sending user profile request: customerId=test-customer-001, correlationId=c091a5407d5d495291a154c0664a79b1
INFO - Received user profile response: correlationId=c091a5407d5d495291a154c0664a79b1, success=true
INFO - User profile request succeeded: customerId=test-customer-001, duration=350ms
```

## 📝 代码变更

### 新增文件
- `UserProfileClientFixed.java` - 固定队列用户画像客户端
- `VectorMemoryClientFixed.java` - 固定队列向量记忆客户端
- `UserProfileRequestMessage.java` - 用户画像请求消息 DTO
- `UserProfileResponseMessage.java` - 用户画像响应消息 DTO
- `MemoryRequestMessage.java` - 记忆请求消息 DTO
- `MemoryResponseMessage.java` - 记忆响应消息 DTO

### 删除文件
- `UserProfileClient.java` - 旧的动态队列客户端
- `VectorMemoryClient.java` - 旧的动态队列客户端
- `UserProfileClientSimple.java` - convertSendAndReceive 版本
- `VectorMemoryClientSimple.java` - convertSendAndReceive 版本

### 修改文件
- `RabbitMQConfig.java` - 添加固定队列声明
- `AiChatService.java` - 使用新的 Fixed 客户端
- `UserProfileMessageListener.java` - 设置响应 correlationId
- `VectorMemoryMessageListener.java` - 设置响应 correlationId

## 🎯 关键经验

1. **RabbitMQ RPC 必须设置 correlationId header** - 客户端通过 correlationId 匹配请求和响应
2. **固定队列比动态队列更可靠** - 避免 DirectReplyTo 机制的复杂配置
3. **使用 CompletableFuture + ConcurrentHashMap 管理异步请求** - 支持并发和超时处理
4. **MessagePostProcessor 设置消息属性** - replyTo 和 correlationId 必须在发送时设置

## 🚀 下一步

1. 配置有效的 DeepSeek API Key
2. 完整测试 AI 聊天流程
3. 实现记忆提取功能
4. 性能优化和监控

## 📚 相关文档

- `docs/ai-services-dependencies.md` - AI 服务依赖关系
- `docs/deepseek-integration.md` - DeepSeek 集成说明
- `docs/ai-services-test-report.md` - 测试报告
- `docs/AI-SERVICES-STATUS.md` - 当前状态
