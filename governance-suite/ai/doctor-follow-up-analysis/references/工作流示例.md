# 工作流示例

## 工作流 1：判断是否进入减重平台期

### 触发问题

- 患者最近两周体重几乎没变化，是否进入平台期
- 最近减重效果差，先帮我看体重趋势

### 推荐步骤

1. 调用 `get_patient_profile`
2. 调用 `get_weight_history`
3. 视情况调用 `calculate_bmi`
4. 调用 `analyze_weight_trend`
5. 调用 `detect_weight_plateau`
6. 输出平台期判断、证据和建议

### 输出重点

- 是否平台期
- 主要证据
- 是否建议加强行为干预
- 是否需要继续观察

## 工作流 2：分析为什么减重效果不好

### 触发问题

- 最近体重降得慢，可能是什么原因
- 帮我看看是不是依从性出了问题

### 推荐步骤

1. 调用 `get_weight_history`
2. 调用 `get_chat_history`
3. 调用 `get_prescription_history`
4. 调用 `analyze_weight_trend`
5. 调用 `analyze_adherence_signals`
6. 如涉及药物，调用 `analyze_medication_adherence`
7. 输出问题归因、证据和下一步动作建议

### 输出重点

- 饮食依从性
- 运动依从性
- 用药依从性
- 证据是否充分
- 还缺哪些信息

## 工作流 3：识别风险并评估是否升级

### 触发问题

- 患者最近聊天里提到头晕、心慌、失眠，需要重点关注吗
- 近期情绪波动大，是否要转人工复核

### 推荐步骤

1. 调用 `get_chat_history`
2. 调用 `triage_red_flags`
3. 调用 `summarize_symptom_timeline`
4. 调用 `extract_structured_symptoms`
5. 视情况调用 `screen_mental_health_risk`
6. 调用 `assess_follow_up_urgency`
7. 证据充分时，再决定是否 `recommend_medical_review`

### 输出重点

- 风险级别
- 症状时间线
- 升级理由
- 推荐响应时窗

## 工作流 4：生成下一步随访动作

### 触发问题

- 帮我整理下一次随访该问什么
- 给出下一步任务建议

### 推荐步骤

1. 完成基础分析
2. 调用 `check_self_monitoring_completeness`
3. 调用 `recommend_follow_up_data_requests`
4. 视情况调用 `generate_patient_education_points`
5. 仅在允许真实动作时，考虑：
   - `send_reminder`
   - `create_follow_up_task`
   - `send_health_report`

### 输出重点

- 需要补采的数据
- 下次随访重点问题
- 适合发送给患者的教育要点
- 是否需要形成正式任务
