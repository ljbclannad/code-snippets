# AI 服务测试报告

## 测试日期
2026-05-14

## 测试环境

### 基础设施状态
✅ MySQL (localhost:3306) - 运行正常
✅ Redis (localhost:36379) - 运行正常  
✅ RabbitMQ (localhost:5672) - 运行正常
✅ Nacos (localhost:8848) - 运行正常
✅ Milvus (localhost:9091) - 运行正常

### 服务状态
✅ user-profile-service (8101) - 运行正常
✅ vector-memory-service (8102) - 运行正常
✅ ai-chat-service (8103) - 运行正常

### 数据库状态
✅ governance.t_customer - 3 条测试数据
✅ governance.t_health_assessment - 3 条测试数据
✅ governance.b_ai_user_memory - 4 条测试数据

## 测试结果

### 1. DeepSeek API 测试
**状态**: ✅ 成功

```bash
curl -X POST https://api.deepseek.com/chat/completions \
  -H "Authorization: Bearer ${DEEPSEEK_API_KEY}" \
  -d '{"model": "deepseek-chat", "messages": [{"role": "user", "content": "你好"}]}'
```

**响应**:
```json
{
  "id": "eca7808d-e688-423d-ad44-d15af6f05ced",
  "model": "deepseek-v4-flash",
  "choices": [{
    "message": {
      "content": "你好！很高兴见到你。有什么我可以帮你的吗？😊"
    }
  }],
  "usage": {
    "total_tokens": 19
  }
}
```

### 2. 用户画像服务 RabbitMQ 通信测试
**状态**: ✅ 成功

**测试方法**: 直接通过 Python + pika 发送 RabbitMQ 消息

**结果**:
- 请求发送成功
- 响应接收成功
- 数据正确: 用户=张三, 年龄=35

**日志**:
```
✓ 已发送请求: 2f08acac-ad17-47bd-8f5c-fac4aacfb4ef
✓ 收到响应: success=True
  用户: 张三, 年龄: 35
✓ 测试成功
```

### 3. AI 聊天服务端到端测试
**状态**: ❌ 失败 - 超时

**测试请求**:
```bash
curl -X POST http://localhost:8103/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "test-customer-001",
    "groupId": "health-group-001",
    "message": "你好"
  }'
```

**错误信息**:
```json
{
  "success": false,
  "errorMessage": "聊天请求处理失败: java.lang.RuntimeException: User profile request timeout after 5000ms",
  "timestamp": 1778722680226
}
```

## 问题分析

### 根本原因
ai-chat-service 的 RabbitMQ 客户端与 user-profile-service/vector-memory-service 之间的 RPC 通信存在问题。

### 详细分析

#### 1. 消息发送正常
从 user-profile-service 日志可以看到：
```
收到用户画像请求: customerId=test-customer-001, replyTo=user.profile.response.89c39493-5c1b-440e-93bb-2ee2f9b0d3c4
用户画像响应已发送到 replyTo 队列: user.profile.response.89c39493-5c1b-440e-93bb-2ee2f9b0d3c4, success=true
```

#### 2. 响应队列存在
```bash
curl -s -u guest:guest http://localhost:15672/api/queues | jq '.[] | select(.name | contains("user.profile.response"))'
```
结果显示队列存在且有 1 个消费者。

#### 3. 但 ai-chat-service 未收到响应
ai-chat-service 日志显示：
```
User profile request timeout: customerId=test-customer-001, correlationId=xxx, timeout=5000ms
```

### 可能的原因

1. **消息转换问题**: 
   - ai-chat-service 期望的响应格式与实际格式不匹配
   - Jackson 反序列化失败

2. **队列监听器配置问题**:
   - `@RabbitListener` 注解配置不正确
   - 队列名称动态生成导致监听器无法绑定

3. **消息确认机制问题**:
   - 消息被消费但未正确处理
   - CompletableFuture 未被正确完成

## 已尝试的解决方案

1. ✅ 修复 DeepSeekClient 编译错误
2. ✅ 优化 MessagePostProcessor 设置 replyTo
3. ✅ 确认 RabbitMQ 连接正常
4. ✅ 确认数据库数据完整
5. ❌ 响应接收问题未解决

## 建议的解决方案

### 方案 1: 简化 RPC 通信（推荐）
使用 Spring AMQP 的 `RabbitTemplate.convertSendAndReceive()` 方法，自动处理 RPC 通信：

```java
public UserProfileDTO requestUserProfile(String customerId) {
    Map<String, Object> request = new HashMap<>();
    request.put("customerId", customerId);
    
    Object response = rabbitTemplate.convertSendAndReceive(
        "user.profile.exchange",
        "user.profile.request",
        request
    );
    
    return objectMapper.convertValue(response, UserProfileDTO.class);
}
```

### 方案 2: 使用固定队列名称
不使用动态队列，改用固定的响应队列：

```java
// 配置固定队列
@Bean
public Queue userProfileResponseQueue() {
    return new Queue("ai-chat.user-profile.response", true);
}

// 监听固定队列
@RabbitListener(queues = "ai-chat.user-profile.response")
public void handleResponse(Message message) {
    // 处理响应
}
```

### 方案 3: 添加详细日志
在响应处理方法中添加详细日志，定位问题：

```java
@RabbitListener(queues = "#{userProfileResponseQueue.name}")
public void handleResponse(Message message) {
    log.info("=== 收到响应消息 ===");
    log.info("CorrelationId: {}", message.getMessageProperties().getCorrelationId());
    log.info("Body: {}", new String(message.getBody()));
    log.info("Pending requests: {}", pendingRequests.keySet());
    // ...
}
```

## 独立服务验证

### 可以独立运行的部分

✅ **user-profile-service** - 完全独立，可单独测试
✅ **vector-memory-service** - 完全独立，可单独测试  
✅ **DeepSeek API** - 外部服务，工作正常

### 需要修复的部分

❌ **ai-chat-service** - RabbitMQ RPC 客户端需要修复

## 结论

1. **基础设施**: 所有基础设施运行正常
2. **数据准备**: 数据库数据完整
3. **服务独立性**: AI 服务完全独立于订单/支付服务
4. **核心问题**: ai-chat-service 的 RabbitMQ RPC 响应接收机制存在问题

## 下一步行动

1. 实施方案 1（使用 `convertSendAndReceive`）- 最简单
2. 或实施方案 3（添加详细日志）- 用于调试
3. 修复后重新测试端到端流程
4. 验证 DeepSeek 集成是否正常工作

## 附录：测试命令

### 启动服务
```bash
# 1. 启动基础设施
docker compose up -d mysql redis rabbitmq nacos milvus

# 2. 初始化数据库
mysql -h 127.0.0.1 -P 3306 -u root -proot < sql/init-all-ai-services.sql

# 3. 启动服务
export DEEPSEEK_API_KEY=sk-your-key
mvn -q -pl user-profile-service spring-boot:run &
mvn -q -pl vector-memory-service spring-boot:run &
mvn -q -pl ai-chat-service spring-boot:run &
```

### 测试命令
```bash
# 测试 DeepSeek API
curl -X POST https://api.deepseek.com/chat/completions \
  -H "Authorization: Bearer ${DEEPSEEK_API_KEY}" \
  -d '{"model": "deepseek-chat", "messages": [{"role": "user", "content": "你好"}]}'

# 测试用户画像服务（Python）
python3 test-01-user-profile.sh

# 测试 AI 聊天服务
curl -X POST http://localhost:8103/api/chat \
  -H "Content-Type: application/json" \
  -d '{"customerId": "test-customer-001", "groupId": "health-group-001", "message": "你好"}'
```
