# AI 服务体系

以患者对话为入口，结合画像、分层记忆、医疗知识检索、医生助手任务和人工复核，并提供独立评测服务。使用 Java 17 与 Spring Boot；依赖版本以[根 pom.xml](../pom.xml)及各模块 POM 为准。

## 部署与验收范围

本项目采用单实例部署：同一环境中，每个 AI 服务仅运行一个应用进程，服务之间继续通过 HTTP、RabbitMQ 等协作。开发与验收遵循[单实例规范](AGENTS.md#部署范围单实例)。并发、重试、审批事务、依赖故障和重启恢复属于本项目范围。

## 模块地图

| 模块 | 默认端口 | 职责与说明 |
| --- | --- | --- |
| [ai-chat-service](ai-chat-service/README.md) | 8100 | 患者入口、上下文、历史与路由编排 |
| user-profile-service | 8101 | 用户画像、候选字段与冲突治理 |
| [vector-memory-service](vector-memory-service/README.md) | 8102 | 长期记忆抽取、检索与生命周期治理 |
| [knowledge-base-service](knowledge-base-service/README.md) | 8103 | 医疗知识生产、检索与文档治理 |
| [doctor-assistant-agent](doctor-assistant-agent/README.md) | 8088 | 任务分析、结构化结果与人工复核 |
| [evaluation-service](evaluation-service/README.md) | 8104 | 样本版本、确定性评分、报告与版本对比 |
| [agent-platform-common](agent-platform-common/README.md) | 无 | 两个入口服务共用的平台协议与运行时 JAR |

## 主链路

患者消息进入聊天服务后，根据条件进入普通对话、知识问答或医生助手任务。画像与长期记忆按需加载。医生助手返回 `patientSafeReply`、`structuredResult` 和 `doctorWorkbenchPayload`；患者端只消费安全回复及患者可见证据。

高风险随访试点和复核进度查询有独立处理路径。Agent 失败是否回退普通对话受配置控制，不能把失败视为已创建复核任务。完整顺序、短路与异常分支见[整合时序](智能对话与医生助手整合时序图.md)。

## 快速开始

以下命令从 `governance-suite` 根目录执行。先按[本地运行与排障](本地运行与排障.md)准备基础设施、数据库与环境变量，再启动：

```bash
bash sh/run/start-ai-services.sh --all
```

聊天工作台地址为 `http://localhost:8100/`；停止整套服务使用 `bash sh/run/stop-ai-services.sh --all`。默认中间件可能指向远端环境，启动前按运行指南确认隔离配置。

## 基础设施依赖

中间件连接、环境隔离和凭据准备集中在[运行指南](本地运行与排障.md#基础设施依赖)。此入口保留供仓库首页链接使用。

## 文档导航

| 需要完成的事 | 阅读入口 |
| --- | --- |
| 修改模块、确定验证范围 | [协作规范](AGENTS.md) |
| 理解请求如何流转 | [整合时序](智能对话与医生助手整合时序图.md) |
| 使用患者聊天、了解幂等与预算 | [聊天服务说明](ai-chat-service/README.md) |
| 配置环境、启动、调试和排障 | [本地运行与排障](本地运行与排障.md) |
| 初始化或升级数据库 | [数据库初始化说明](数据库初始化说明.md) |
| 演示患者登记与医生审批 | [高风险随访演示](高风险随访演示.md) |
| 选择测试及了解验收边界 | [测试分层](人工智能服务测试分层.md) |
| 采集真实调用并参与评测 | [真实调用采集](真实调用采集说明.md) |
| 学习设计取舍与评估方法 | [学习与设计参考](学习与设计参考.md) |

## 高风险随访提醒试点

试点默认关闭。启用条件、服务间认证和审批投递契约见[医生助手说明](doctor-assistant-agent/README.md#高风险随访提醒)，双角色操作见[随访演示](高风险随访演示.md)。

## 患者聊天入口与会话归属

身份校验、匿名 Cookie、工作台交互与历史访问说明已集中到[聊天服务说明](ai-chat-service/README.md#患者聊天入口与会话归属)；已有环境迁移见[数据库指南](数据库初始化说明.md#已有环境升级)。

## 固定业务回归

[固定回归](evaluation-service/regression/README.md)覆盖路由、幂等、依赖失败与现有审批能力。报告区分已执行测试与尚未覆盖范围，离线测试通过不能替代真实模型质量或完整环境联调。
