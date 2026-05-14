# DeepSeek 集成说明

## 概述

已将 AI 聊天服务从 Dify API 切换到 DeepSeek API 直接调用。原 Dify 代码已注释保留，可随时切换回来。

## 主要变更

### 1. 新增 DeepSeekClient
**文件**: `ai-chat-service/src/main/java/com/lx/governance/aichat/client/DeepSeekClient.java`

**功能**:
- 直接调用 DeepSeek Chat API
- 将用户画像和记忆构建为 System Prompt
- 返回格式兼容原 DifyResponse，无需修改下游代码

### 2. 修改 AiChatService
**文件**: `ai-chat-service/src/main/java/com/lx/governance/aichat/service/AiChatService.java`

**变更**:
```java
// 当前使用 DeepSeek
DifyResponse difyResponse = deepSeekClient.chat(request, profile, memories);

// 原 Dify 调用（已注释，保留代码）
// DifyResponse difyResponse = difyClient.chat(request, profile, memories);
```

### 3. 更新 RestClientConfig
**文件**: `ai-chat-service/src/main/java/com/lx/governance/aichat/config/RestClientConfig.java`

**新增**:
- `deepSeekRestClient()` Bean
- DeepSeek API 配置（base-url, api-key, timeout）

### 4. 更新配置文件
**文件**: `ai-chat-service/src/main/resources/application.yml`

**新增配置**:
```yaml
deepseek:
  api:
    base-url: ${DEEPSEEK_API_BASE_URL:https://api.deepseek.com}
    key: ${DEEPSEEK_API_KEY:your-deepseek-api-key}
    timeout: 60000
  model: ${DEEPSEEK_MODEL:deepseek-chat}
```

## DeepSeek API 调用流程

```
用户请求
   ↓
AiChatService
   ↓
并行获取上下文
   ├─→ UserProfileClient (用户画像)
   └─→ VectorMemoryClient (历史记忆)
   ↓
DeepSeekClient.chat()
   ↓
构建 System Prompt:
   ┌─────────────────────────────────────┐
   │ 你是一个专业的健康顾问助手...        │
   │                                     │
   │ ## 用户基本信息                      │
   │ - 姓名: 张三                         │
   │ - 年龄: 35岁                         │
   │ - 性别: 男                           │
   │                                     │
   │ ## 健康档案                          │
   │ - BMI: 24.5                         │
   │ - 血压: 140/90 mmHg                 │
   │ - 血糖: 5.8 mmol/L                  │
   │                                     │
   │ ## 重要健康记录                      │
   │ - [ALLERGY] 海鲜过敏: 对虾、蟹过敏   │
   │                                     │
   │ ## 历史对话记忆                      │
   │ - 用户最近提到血压偏高... (0.95)     │
   │ - 用户偏好清淡饮食... (0.88)         │
   └─────────────────────────────────────┘
   ↓
调用 DeepSeek API
   POST https://api.deepseek.com/chat/completions
   {
     "model": "deepseek-chat",
     "messages": [
       {"role": "system", "content": "..."},
       {"role": "user", "content": "我想了解如何改善血压"}
     ],
     "temperature": 0.7,
     "max_tokens": 2000
   }
   ↓
DeepSeek 返回
   {
     "choices": [{
       "message": {
         "content": "根据您的健康档案..."
       }
     }],
     "usage": {
       "prompt_tokens": 450,
       "completion_tokens": 320,
       "total_tokens": 770
     }
   }
   ↓
转换为 DifyResponse 格式
   ↓
返回给用户
```

## 配置说明

### 环境变量

**必须配置**:
```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
```

**可选配置**:
```bash
export DEEPSEEK_API_BASE_URL=https://api.deepseek.com  # 默认值
export DEEPSEEK_MODEL=deepseek-chat                     # 默认值
```

### 获取 DeepSeek API Key

1. 访问 [DeepSeek 开放平台](https://platform.deepseek.com/)
2. 注册/登录账号
3. 进入 API Keys 页面
4. 创建新的 API Key
5. 复制 Key 并配置到环境变量

### 模型选择

DeepSeek 提供以下模型：
- `deepseek-chat`: 通用对话模型（推荐）
- `deepseek-coder`: 代码专用模型

## 对比：Dify vs DeepSeek

| 特性 | Dify | DeepSeek |
|------|------|----------|
| **集成方式** | 通过 Dify 平台 | 直接调用 API |
| **上下文注入** | inputs 参数 | System Prompt |
| **会话管理** | Dify 管理 | 自行管理 |
| **成本** | Dify 费用 + LLM 费用 | 仅 LLM 费用 |
| **灵活性** | 受限于 Dify | 完全可控 |
| **超时时间** | 30秒 | 60秒 |
| **Token 统计** | 有限 | 详细 |

## 切换回 Dify

如需切换回 Dify，只需修改 `AiChatService.java`:

```java
// 使用 Dify
DifyResponse difyResponse = difyClient.chat(request, profile, memories);

// 使用 DeepSeek（注释掉）
// DifyResponse difyResponse = deepSeekClient.chat(request, profile, memories);
```

然后配置 Dify 环境变量：
```bash
export DIFY_API_KEY=app-xxxxxxxxxxxxxxxx
```

## 测试

### 启动服务
```bash
# 设置 DeepSeek API Key
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx

# 启动服务
mvn -q -pl ai-chat-service spring-boot:run
```

### 测试请求
```bash
curl -X POST http://localhost:8103/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "test-customer-001",
    "groupId": "health-group-001",
    "message": "我想了解如何改善血压问题"
  }'
```

### 预期响应
```json
{
  "success": true,
  "message": "success",
  "data": {
    "answer": "根据您的健康档案，您的血压为140/90mmHg，属于1级高血压...",
    "conversationId": "conv-xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
  },
  "traceId": "trace-xxxxxxxx"
}
```

## 日志示例

```
INFO  - Calling DeepSeek API for customer: test-customer-001, conversationId: null
INFO  - DeepSeek API call completed in 2345ms, tokens: prompt=450, completion=320, total=770
INFO  - 聊天请求处理完成 - customerId: test-customer-001, correlationId: xxx, 耗时: 7890ms
```

## 性能对比

### Dify 方案
- 上下文获取: ~5秒
- Dify API 调用: ~30秒
- **总耗时**: ~35秒

### DeepSeek 方案
- 上下文获取: ~5秒
- DeepSeek API 调用: ~2-5秒
- **总耗时**: ~7-10秒

**性能提升**: 约 70-80%

## 成本对比

### Dify 方案
- Dify 平台费用: $X/月
- LLM 调用费用: 按 Token 计费

### DeepSeek 方案
- DeepSeek API 费用: ¥1/百万 tokens (输入), ¥2/百万 tokens (输出)
- 无平台费用

**示例**: 1000次对话，平均每次 500 tokens 输入 + 300 tokens 输出
- 成本: (500 * 1000 / 1000000 * 1) + (300 * 1000 / 1000000 * 2) = ¥1.1

## 注意事项

1. **API Key 安全**: 不要将 API Key 硬编码到代码中，使用环境变量
2. **超时设置**: DeepSeek 超时设置为 60秒，比 Dify 的 30秒更长
3. **Token 限制**: DeepSeek 单次请求最大 2000 tokens 输出
4. **并发限制**: 注意 DeepSeek API 的并发限制和速率限制
5. **错误处理**: 已实现异常捕获和日志记录

## 故障排查

### 问题: API Key 无效
```
Failed to call DeepSeek API: 401 Unauthorized
```
**解决**: 检查 `DEEPSEEK_API_KEY` 环境变量是否正确配置

### 问题: 超时
```
Failed to call DeepSeek API: Read timed out
```
**解决**: 
- 检查网络连接
- 增加超时时间（修改 `deepseek.api.timeout`）

### 问题: Token 超限
```
Failed to call DeepSeek API: max_tokens exceeded
```
**解决**: 
- 减少 System Prompt 长度
- 限制记忆数量（当前最多 5 条）
- 增加 `max_tokens` 参数

## 未来优化

1. **流式响应**: 支持 SSE 流式返回，提升用户体验
2. **会话管理**: 实现多轮对话的上下文管理
3. **Prompt 优化**: 根据实际效果优化 System Prompt
4. **缓存策略**: 对频繁访问的用户画像进行缓存
5. **降级策略**: DeepSeek 不可用时的降级方案
6. **A/B 测试**: 对比 Dify 和 DeepSeek 的效果差异
