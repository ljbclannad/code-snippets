# Spring Boot 3.x 升级设计文档

**日期：** 2026-05-11  
**项目：** governance-suite  
**目标：** 将整个微服务项目从 Spring Boot 2.7.18 升级到 Spring Boot 3.2.5

## 1. 项目背景

governance-suite 是一个面向分布式知识学习的 Spring Cloud 演示项目，包含 7 个微服务模块：
- common（公共模块）
- gateway（网关）
- user-service（用户服务）
- order-service（订单服务）
- payment-service（支付服务）
- inventory-service（库存服务）
- outbox-consumer（消息发送消费者）

当前使用 Spring Boot 2.7.18，需要升级到 Spring Boot 3.x 以获得更好的性能、安全性和长期支持。

## 2. 升级目标

### 2.1 核心目标
- 将 Spring Boot 从 2.7.18 升级到 3.2.5
- 将 Spring Cloud 从 2021.0.9 升级到 2023.0.1
- 将 Spring Cloud Alibaba 从 2021.0.5.0 升级到 2022.0.0.0
- 完成 javax.* 到 jakarta.* 的命名空间迁移
- 升级相关中间件客户端到兼容版本
- 为所有服务添加 Actuator 健康检查端点
- 保持项目功能完整性，确保所有分布式特性正常工作

### 2.2 非目标
- 不添加自动化测试（项目采用手动验证方式）
- 不进行故障注入测试
- 不重构现有业务逻辑
- 不升级 Java 版本（保持 Java 17）

## 3. 版本升级策略

### 3.1 核心框架版本

| 组件 | 当前版本 | 目标版本 | 说明 |
|------|---------|---------|------|
| Spring Boot | 2.7.18 | 3.2.5 | LTS 版本，生态成熟 |
| Spring Cloud | 2021.0.9 | 2023.0.1 | 对应 Spring Boot 3.2.x |
| Spring Cloud Alibaba | 2021.0.5.0 | 2022.0.0.0 | 官方推荐的 3.2 兼容版本 |
| Java | 17 | 17 | 保持不变 |

### 3.2 中间件客户端版本

| 组件 | 当前版本 | 目标版本 | 兼容性说明 |
|------|---------|---------|-----------|
| Seata | 1.7.1 | 2.0.0 | 官方支持 Spring Boot 3.x |
| MyBatis Spring Boot Starter | 2.3.2 | 3.0.3 | 支持 jakarta.* 命名空间 |
| ShardingSphere | 5.5.0 | 5.5.0 | 已支持 Spring Boot 3.x，保持不变 |
| Lombok | 1.18.36 | 1.18.36 | 保持不变 |
| MySQL Connector | 8.0.33 | 8.0.33 | 保持不变 |

### 3.3 版本选择原则

1. **Spring Boot 3.2.5** - 选择 3.2.x 系列的稳定版本，避免 3.3/3.4 的新特性带来的不确定性
2. **Spring Cloud 2023.0.1** - 官方明确对应 Spring Boot 3.2.x 的版本
3. **Spring Cloud Alibaba 2022.0.0.0** - 根据官方兼容性矩阵，这是支持 Spring Boot 3.2.x 的稳定版本
4. **Seata 2.0.0** - 官方首个支持 Spring Boot 3.x 的大版本
5. **MyBatis 3.0.3** - 支持 jakarta.* 命名空间的首个稳定版本

### 3.4 迁移策略

采用**一次性全量升级**策略：
- 一次性升级所有依赖到 Spring Boot 3.2.x 兼容版本
- 批量替换所有 javax.* → jakarta.* 导入
- 避免中间状态，减少版本混搭带来的兼容性问题

**选择理由：**
1. 这是学习项目，可以承受一次性变更的风险
2. Spring Boot 3.x 生态已经成熟，主流依赖都有稳定版本
3. 项目规模适中（7个服务），全量验证成本可控
4. javax → jakarta 改动量不大（仅 10 处）

## 4. 代码迁移清单

### 4.1 命名空间迁移（javax → jakarta）

根据代码扫描结果，需要修改 10 处 javax.* 导入：

**Servlet API（5处）**
- `common/src/main/java/com/lx/governance/common/web/TraceIdFilter.java`
  - `javax.servlet.FilterChain` → `jakarta.servlet.FilterChain`
  - `javax.servlet.ServletException` → `jakarta.servlet.ServletException`
  - `javax.servlet.http.HttpServletRequest` → `jakarta.servlet.http.HttpServletRequest`
  - `javax.servlet.http.HttpServletResponse` → `jakarta.servlet.http.HttpServletResponse`

**注解（1处）**
- `gateway/src/main/java/com/lx/governance/gateway/config/SentinelGatewayConfig.java`
  - `javax.annotation.PostConstruct` → `jakarta.annotation.PostConstruct`

**JDBC（3处）**
- `payment-service/src/main/java/com/lx/governance/payment/config/IdSegmentDataSourceConfig.java`
- `order-service/src/main/java/com/lx/governance/order/config/IdSegmentDataSourceConfig.java`
- `user-service/src/main/java/com/lx/governance/user/config/IdSegmentDataSourceConfig.java`
  - `javax.sql.DataSource` → `jakarta.sql.DataSource`

**加密 API（2处 - 无需修改）**
- `common/src/main/java/com/lx/governance/common/auth/AuthTokenService.java`
  - `javax.crypto.Mac` 和 `javax.crypto.spec.SecretKeySpec` 属于 JDK 核心包，不需要改为 jakarta

### 4.2 Actuator 健康检查端点

为所有 6 个服务添加 Actuator 支持：

**需要添加依赖的服务：**
- gateway
- user-service
- order-service
- payment-service
- inventory-service
- outbox-consumer

**依赖配置：**
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

**application.yml 配置：**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always
  health:
    defaults:
      enabled: true
```

**暴露的端点：**
- `http://localhost:{port}/actuator/health` - 健康检查
- `http://localhost:{port}/actuator/info` - 服务信息

### 4.3 配置文件调整

**可能需要调整的配置：**
1. Spring Cloud Gateway 的过滤器配置格式（如果有变化）
2. Sentinel 的配置属性名称（如果有变化）
3. Seata 的配置属性名称（2.0.0 可能有调整）

### 4.4 潜在的 API 变更

**Spring Boot 3.x 的主要变更：**
1. `@ConstructorBinding` 需要在类级别添加（如果使用了 @ConfigurationProperties）
2. `spring.redis.*` 配置属性可能有细微调整
3. Actuator 端点的默认暴露策略可能有变化

## 5. 迁移执行步骤

### 步骤 1：更新父 POM 依赖版本

修改 `pom.xml` 的 properties 部分：

```xml
<properties>
  <java.version>17</java.version>
  <spring-boot.version>3.2.5</spring-boot.version>
  <spring-cloud.version>2023.0.1</spring-cloud.version>
  <spring-cloud-alibaba.version>2022.0.0.0</spring-cloud-alibaba.version>
  <shardingsphere.version>5.5.0</shardingsphere.version>
  <mybatis-spring-boot-starter.version>3.0.3</mybatis-spring-boot-starter.version>
  <seata.version>2.0.0</seata.version>
  <maven.compiler.release>${java.version}</maven.compiler.release>
  <mysql.version>8.0.33</mysql.version>
</properties>
```

### 步骤 2：为所有服务添加 Actuator 依赖和配置

**2.1 在每个服务的 pom.xml 中添加依赖**

为以下服务添加 spring-boot-starter-actuator 依赖：
- gateway/pom.xml
- user-service/pom.xml
- order-service/pom.xml
- payment-service/pom.xml
- inventory-service/pom.xml
- outbox-consumer/pom.xml

**2.2 在每个服务的 application.yml 中添加配置**

为以上 6 个服务的 application.yml 添加 management 配置段。

### 步骤 3：批量替换 javax → jakarta

使用 find + sed 批量替换 Java 源文件中的导入语句：
- `javax.servlet` → `jakarta.servlet`
- `javax.annotation` → `jakarta.annotation`
- `javax.sql` → `jakarta.sql`

**注意：** `javax.crypto` 不替换（JDK 核心包）

### 步骤 4：清理并重新编译

```bash
# 清理所有构建产物
mvn clean

# 重新编译（跳过测试）
mvn -T 1C package -DskipTests
```

### 步骤 5：处理编译错误

逐个模块检查编译错误，常见问题：
1. API 方法签名变更
2. 废弃方法需要替换
3. 配置类需要调整
4. 依赖冲突需要排除

### 步骤 6：启动基础设施

```bash
docker compose up -d
```

确保以下基础设施正常启动：
- MySQL (localhost:3306)
- Redis (localhost:36379)
- RabbitMQ (localhost:5672, 管理界面 localhost:15672)
- Nacos (http://localhost:8848)
- Seata (localhost:8091)
- Sentinel Dashboard (http://localhost:8858)

### 步骤 7：逐个启动服务并验证

按依赖顺序启动服务，每个服务启动后立即验证：

**7.1 启动 gateway (8080)**
```bash
mvn -q -pl gateway spring-boot:run
```
验证：
- 日志中无严重错误
- `curl http://localhost:8080/actuator/health` 返回 `{"status":"UP"}`
- Nacos 控制台能看到 gateway 服务注册

**7.2 启动 user-service (8084)**
```bash
mvn -q -pl user-service spring-boot:run
```
验证：
- 日志中无严重错误
- `curl http://localhost:8084/actuator/health` 返回 `{"status":"UP"}`
- Nacos 控制台能看到 user-service 服务注册

**7.3 启动 inventory-service (8083)**
```bash
mvn -q -pl inventory-service spring-boot:run
```
验证：
- 日志中无严重错误
- `curl http://localhost:8083/actuator/health` 返回 `{"status":"UP"}`
- Nacos 控制台能看到 inventory-service 服务注册

**7.4 启动 payment-service (8082)**
```bash
mvn -q -pl payment-service spring-boot:run
```
验证：
- 日志中无严重错误
- `curl http://localhost:8082/actuator/health` 返回 `{"status":"UP"}`
- Nacos 控制台能看到 payment-service 服务注册

**7.5 启动 order-service (8081)**
```bash
mvn -q -pl order-service spring-boot:run
```
验证：
- 日志中无严重错误
- `curl http://localhost:8081/actuator/health` 返回 `{"status":"UP"}`
- Nacos 控制台能看到 order-service 服务注册

**7.6 启动 outbox-consumer (8090)**
```bash
mvn -q -pl outbox-consumer spring-boot:run
```
验证：
- 日志中无严重错误
- `curl http://localhost:8090/actuator/health` 返回 `{"status":"UP"}`
- Nacos 控制台能看到 outbox-consumer 服务注册

### 步骤 8：端到端功能验证

按 README.md 中的 curl 示例测试完整订单流程：

**8.1 用户注册**
```bash
curl -X POST http://localhost:8080/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"password123"}'
```

**8.2 用户登录获取 token**
```bash
curl -X POST http://localhost:8080/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"password123"}'
```
保存返回的 token。

**8.3 创建订单（触发 Seata 分布式事务）**
```bash
curl -X POST http://localhost:8080/api/order/create \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {token}" \
  -d '{"skuId":1001,"quantity":2}'
```

**8.4 验证库存扣减**
检查 inventory-service 日志，确认库存扣减成功。

**8.5 验证支付记录**
检查 payment-service 日志，确认支付准备和确认成功。

**8.6 验证 outbox 消息发送到 RabbitMQ**
- 检查 outbox-consumer 日志，确认消息发送成功
- 登录 RabbitMQ 管理界面 (http://localhost:15672)，确认消息已投递

## 6. 验证标准

### 6.1 编译验证
- 所有模块编译成功，无错误
- 无废弃 API 警告（或已知可接受的警告）

### 6.2 启动验证
- 所有 6 个服务能正常启动
- 所有服务能在 Nacos 注册成功
- 所有服务的 Actuator 健康检查返回 UP 状态

### 6.3 功能验证
- 用户注册、登录功能正常
- 订单创建流程完整（包括库存扣减、支付准备、支付确认）
- Seata 分布式事务正常工作
- Outbox 消息能成功发送到 RabbitMQ
- Gateway 路由、限流、熔断功能正常
- 分表查询功能正常（ShardingSphere）

### 6.4 中间件集成验证
- Nacos 服务发现正常
- Seata 分布式事务协调正常
- Redis 缓存和幂等性检查正常
- RabbitMQ 消息投递正常
- Sentinel 限流熔断正常

## 7. 回滚方案

如果升级过程中遇到无法解决的问题，回滚步骤：

1. 使用 git 恢复所有修改：
   ```bash
   git checkout .
   git clean -fd
   ```

2. 重新编译原版本：
   ```bash
   mvn clean package -DskipTests
   ```

3. 重新启动服务验证

## 8. 风险评估

### 8.1 高风险项
- **Seata 2.0.0 兼容性**：Seata 从 1.x 升级到 2.x 是大版本升级，可能有配置和 API 变更
- **Spring Cloud Alibaba 兼容性**：2022.0.0.0 是相对较新的版本，可能存在未知问题

### 8.2 中风险项
- **MyBatis 3.0.3 兼容性**：命名空间变更可能影响 XML 映射文件
- **Gateway 过滤器**：Spring Cloud Gateway 3.x 可能有过滤器 API 变更

### 8.3 低风险项
- **javax → jakarta 替换**：改动量小（10处），风险可控
- **ShardingSphere**：5.5.0 已支持 Spring Boot 3.x，保持版本不变

### 8.4 缓解措施
1. 采用一次性全量升级，避免版本混搭
2. 逐个服务启动验证，快速定位问题
3. 保持 git 历史清晰，便于回滚
4. 参考官方迁移指南和兼容性矩阵

## 9. 后续优化建议

升级完成后，可以考虑的优化方向：

1. **性能优化**：利用 Spring Boot 3.x 的虚拟线程特性（Java 21+）
2. **可观测性增强**：集成 Micrometer Tracing 替代自定义 TraceId
3. **配置管理**：使用 Spring Cloud Config 统一配置管理
4. **文档更新**：更新 README.md 和各个 lab 文档中的版本信息

## 10. 参考资料

- [Spring Boot 3.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide)
- [Spring Cloud 2023.0.0 Release Notes](https://github.com/spring-cloud/spring-cloud-release/wiki/Spring-Cloud-2023.0-Release-Notes)
- [Spring Cloud Alibaba Version Compatibility](https://github.com/alibaba/spring-cloud-alibaba/wiki/版本说明)
- [Seata 2.0.0 Release Notes](https://github.com/seata/seata/releases/tag/v2.0.0)
- [MyBatis Spring Boot 3.0 Migration](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)
