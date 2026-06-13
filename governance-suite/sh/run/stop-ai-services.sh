#!/bin/bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "${SCRIPT_DIR}/../.." && pwd)
LOG_DIR="${ROOT_DIR}/logs/ai-services"

SERVICES=(
  "user-profile-service:8101"
  "vector-memory-service:8102"
  "ai-chat-service:8100"
  "doctor-assistant-agent:8088"
)

stop_pid_if_running() {
  local pid="$1"
  local label="$2"

  if [[ -z "${pid}" ]] || ! kill -0 "${pid}" 2>/dev/null; then
    return 1
  fi

  echo "停止 ${label} (PID: ${pid})..."
  kill "${pid}" 2>/dev/null || true

  for _ in {1..5}; do
    if ! kill -0 "${pid}" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done

  echo "  ${label} 仍在运行，执行强制停止"
  kill -9 "${pid}" 2>/dev/null || true
  return 0
}

force_kill_port() {
  local port="$1"
  local service="$2"
  local pids

  pids=$(lsof -ti tcp:"${port}" 2>/dev/null || true)
  if [[ -z "${pids}" ]]; then
    echo "端口 ${port} 空闲 (${service})"
    return 0
  fi

  while IFS= read -r pid; do
    [[ -z "${pid}" ]] && continue
    if kill -0 "${pid}" 2>/dev/null; then
      echo "强制清理端口 ${port} 占用 (${service}, PID: ${pid})"
      kill -9 "${pid}" 2>/dev/null || true
    fi
  done <<< "${pids}"
}

echo "=== 停止 AI 主链路服务 ==="

for entry in "${SERVICES[@]}"; do
  IFS=":" read -r service port <<< "${entry}"
  pid_file="${LOG_DIR}/${service}.pid"
  handled=0

  if [[ -f "${pid_file}" ]]; then
    pid=$(cat "${pid_file}")
    if stop_pid_if_running "${pid}" "${service}"; then
      handled=1
    else
      echo "清理 ${service}: PID 文件已过期 (${pid})"
    fi
    rm -f "${pid_file}"
  else
    echo "跳过 ${service}: 未找到 PID 文件"
  fi

  if [[ "${handled}" -eq 0 ]]; then
    echo "  ${service} 未通过 PID 停止，改为检查端口 ${port}"
  fi

  force_kill_port "${port}" "${service}"
done

echo "=== 停止完成 ==="
