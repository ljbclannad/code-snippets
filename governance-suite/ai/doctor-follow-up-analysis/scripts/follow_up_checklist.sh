#!/usr/bin/env bash
set -euo pipefail

task_type="${1:-}"

if [[ -z "${task_type}" ]]; then
  cat <<'EOF'
用法: ./scripts/follow_up_checklist.sh <task-type>

可选 task-type:
  plateau
  adherence
  risk
  next-action
EOF
  exit 1
fi

case "${task_type}" in
  plateau)
    cat <<'EOF'
[平台期判断检查清单]
1. 是否已有 customerId
2. 是否已获取最近 14-30 天体重记录
3. 是否已完成体重趋势分析
4. 是否已完成平台期识别
5. 是否需要补充聊天记录确认行为因素
EOF
    ;;
  adherence)
    cat <<'EOF'
[依从性分析检查清单]
1. 是否已获取体重历史
2. 是否已获取聊天记录
3. 是否已获取处方历史
4. 是否已完成依从性分析
5. 是否明确缺失数据和下一步补采项
EOF
    ;;
  risk)
    cat <<'EOF'
[风险升级检查清单]
1. 是否已获取近期聊天记录
2. 是否已完成红旗症状识别
3. 是否已完成症状时间线提取
4. 是否已完成紧急度评估
5. 是否具备足够证据支持人工复核建议
EOF
    ;;
  next-action)
    cat <<'EOF'
[下一步动作检查清单]
1. 是否已完成基础分析
2. 是否已识别数据缺口
3. 是否已生成随访补采建议
4. 是否允许真实写操作
5. 是否需要提醒、任务或报告
EOF
    ;;
  *)
    echo "不支持的 task-type: ${task_type}" >&2
    exit 2
    ;;
esac
