---
name: doctor-follow-up-analysis
description: 面向减重管理随访场景的通用 Agent 技能说明。用于在患者资料、体重历史、聊天记录、处方历史和健康知识之间做结构化分析，判断减重进展、停滞期、依从性、风险分级与下一步随访动作。适用于“分析最近 2 到 4 周减重效果”“判断是否进入平台期”“识别红旗症状并评估是否需要人工复核”“生成随访建议或任务草案”等任务。
---

# 减重随访分析技能

## 概述

将本技能当作“减重管理随访场景的执行手册”使用，而不是单个工具说明。
先收集证据，再做趋势判断，再决定是否需要随访动作或人工复核，避免直接跳到高风险结论。

## 适用任务

- 近 2 到 4 周减重进展分析
- 减重停滞期识别
- 饮食、运动、用药依从性分析
- 近期症状、情绪、红旗信号排查
- 下一步随访内容、提醒或任务建议生成
- 需要医生审核的高风险建议草拟

## 输入要求

至少确认以下信息中的一部分，缺失时优先补数据而不是强行下结论：

- `customerId` 或等价患者标识
- 分析时间窗，默认优先看最近 14 到 30 天
- 当前任务目标，例如平台期判断、依从性分析、风险排查
- 是否允许产生写操作，例如提醒、任务创建、报告发送
- 是否允许触发高风险建议链路

## 工作流

### 1. 先明确任务目标

- 如果目标是“判断减重是否有效”，优先关注体重趋势、BMI、平台期。
- 如果目标是“看为什么效果不好”，优先关注依从性、聊天证据、症状和情绪。
- 如果目标是“给出下一步动作”，先完成分析，再决定是否进入提醒、随访任务或人工复核。

### 2. 先走读操作，再走写操作

优先按下面顺序组合低风险只读工具：

- `get_patient_profile`
- `get_weight_history`
- `get_chat_history`
- `get_prescription_history`
- `get_health_knowledge`

只有在证据足够后，才考虑写操作或高风险动作。

### 3. 做结构化分析

常见分析顺序：

1. 用 `calculate_bmi` 得到当前基础体征指标。
2. 用 `analyze_weight_trend` 判断总体下降、波动或反弹。
3. 用 `detect_weight_plateau` 判断是否进入平台期。
4. 用 `analyze_adherence_signals` 判断饮食和运动依从性。
5. 如涉及症状或安全问题，再调用：
   - `triage_red_flags`
   - `summarize_symptom_timeline`
   - `extract_structured_symptoms`
   - `screen_mental_health_risk`
   - `assess_follow_up_urgency`
6. 如需要补齐缺口，调用 `check_self_monitoring_completeness` 与 `recommend_follow_up_data_requests`。

### 4. 决定动作层级

根据证据强度和风险级别，按以下顺序决策：

- 仅输出分析结论，不做动作
- 输出患者教育要点：`generate_patient_education_points`
- 发送提醒：`send_reminder`
- 创建随访任务：`create_follow_up_task`
- 发送阶段报告：`send_health_report`
- 起草高风险方案：`draft_weight_management_plan`
- 建议转人工医学复核：`recommend_medical_review`

### 5. 输出结构化结果

输出至少覆盖以下字段：

- `summary`：一句话总结
- `keyFindings`：关键发现
- `riskLevel`：低、中、高
- `recommendedActions`：建议动作
- `supportingEvidence`：证据清单
- `uncertainties`：不确定项
- `nextDataNeeded`：下一步需要补充的数据

## 安全边界

- 不把本技能当成诊断或处方替代。
- 遇到急症、严重不适、自伤风险、明显药物不良反应时，优先升级而不是继续常规随访建议。
- 没有足够证据时，不触发高风险建议，也不输出过度确定的因果判断。
- 先读取、再分析、后执行写操作；避免一上来就 `send_reminder` 或 `create_follow_up_task`。
- 对可能影响治疗方案的建议，默认走人工审核链路。

## 参考资料

- 工具目录与分级：见 `references/tool-catalog.md`
- 工具调用规定与边界：见 `references/tool-invocation-policy.md`
- 场景化工作流与示例：见 `references/workflow-examples.md`
- 标准输出 JSON 模板：见 `references/output-schema.json`
- 如果需要最新工具入参与风险等级，以项目源码为准：
  `doctor-assistant-agent/src/main/java/com/health/agent/scene/doctor/tool/DoctorToolDefinitionRegistry.java`

## 使用建议

- 先读 `references/tool-catalog.md`，确认有哪些工具可用。
- 涉及真实写操作或高风险动作前，先读 `references/tool-invocation-policy.md`。
- 再按 `references/workflow-examples.md` 选择最接近当前任务的流程。
- 需要结构化返回结果时，按 `references/output-schema.json` 输出。
- 仅在任务确实需要时才进入中风险和高风险工具。
