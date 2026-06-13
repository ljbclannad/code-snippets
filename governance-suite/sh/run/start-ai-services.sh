#!/bin/bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "${SCRIPT_DIR}/../.." && pwd)
LOG_DIR="${ROOT_DIR}/logs/ai-services"
RESTART_MODE=0

SERVICES=(
  "user-profile-service:8101:/actuator/health"
  "vector-memory-service:8102:/actuator/health"
  "ai-chat-service:8100:/actuator/health"
)

REQUIRED_CONTAINERS=(
  "gov-mysql"
  "gov-redis"
  "gov-rabbitmq"
  "gov-nacos"
  "gov-milvus"
)

usage() {
  cat <<'EOF'
用法:
  ./sh/run/start-ai-services.sh [--restart]

参数:
  --restart   先停止并清理 8100~8102 对应服务，再重新启动
EOF
}

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "错误: ${name} 未设置"
    echo "请先执行: export ${name}=your-key"
    exit 1
  fi
}

cleanup_stale_pid() {
  local service="$1"
  local pid_file="${LOG_DIR}/${service}.pid"

  if [[ -f "${pid_file}" ]]; then
    local pid
    pid=$(cat "${pid_file}")
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
      return 0
    fi
    rm -f "${pid_file}"
  fi

  return 1
}

is_port_in_use() {
  local port="$1"
  lsof -ti tcp:"${port}" >/dev/null 2>&1
}

kill_port_occupants() {
  local port="$1"
  local service="$2"
  local pids

  pids=$(lsof -ti tcp:"${port}" 2>/dev/null || true)
  if [[ -z "${pids}" ]]; then
    return 0
  fi

  while IFS= read -r pid; do
    [[ -z "${pid}" ]] && continue
    if kill -0 "${pid}" 2>/dev/null; then
      echo "清理端口 ${port} 占用后再启动 ${service} (PID: ${pid})"
      kill "${pid}" 2>/dev/null || true

      for _ in {1..5}; do
        if ! kill -0 "${pid}" 2>/dev/null; then
          break
        fi
        sleep 1
      done

      if kill -0 "${pid}" 2>/dev/null; then
        echo "  端口 ${port} 的占用进程仍在运行，执行强制停止"
        kill -9 "${pid}" 2>/dev/null || true
      fi
    fi
  done <<< "${pids}"
}

stop_pid_if_running() {
  local pid="$1"
  local service="$2"

  if [[ -z "${pid}" ]] || ! kill -0 "${pid}" 2>/dev/null; then
    return 1
  fi

  echo "重启模式: 先停止 ${service} (PID: ${pid})"
  kill "${pid}" 2>/dev/null || true

  for _ in {1..5}; do
    if ! kill -0 "${pid}" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done

  echo "  ${service} 进程仍在运行，执行强制停止"
  kill -9 "${pid}" 2>/dev/null || true
  return 0
}

wait_for_health() {
  local service="$1"
  local port="$2"
  local path="$3"
  local timeout="${4:-90}"
  local url="http://localhost:${port}${path}"
  local start_ts
  start_ts=$(date +%s)

  while true; do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      echo "  ${service} 健康检查通过: ${url}"
      return 0
    fi

    if (( $(date +%s) - start_ts >= timeout )); then
      echo "  ${service} 启动超时，请检查日志: ${LOG_DIR}/${service}.log"
      return 1
    fi

    sleep 2
  done
}

start_service() {
  local service="$1"
  local port="$2"
  local health_path="$3"
  local pid_file="${LOG_DIR}/${service}.pid"
  local log_file="${LOG_DIR}/${service}.log"

  if [[ "${RESTART_MODE}" -eq 1 ]]; then
    if [[ -f "${pid_file}" ]]; then
      local pid
      pid=$(cat "${pid_file}")
      stop_pid_if_running "${pid}" "${service}" || true
      rm -f "${pid_file}"
    fi
    kill_port_occupants "${port}" "${service}"
  else
    if cleanup_stale_pid "${service}"; then
      echo "跳过 ${service}: 已在运行 (PID: $(cat "${pid_file}"))"
      return 0
    fi
  fi

  if is_port_in_use "${port}"; then
    kill_port_occupants "${port}" "${service}"
  fi

  if is_port_in_use "${port}"; then
    echo "错误: 端口 ${port} 清理后仍被占用，无法启动 ${service}"
    exit 1
  fi

  echo "启动 ${service} (${port})..."
  nohup mvn -q -pl "${service}" spring-boot:run > "${log_file}" 2>&1 &
  local pid=$!
  echo "${pid}" > "${pid_file}"

  if ! wait_for_health "${service}" "${port}" "${health_path}"; then
    echo "  ${service} 启动失败，最近日志如下:"
    tail -n 80 "${log_file}" || true
    exit 1
  fi
}

echo "=== 启动 AI 对话服务 ==="
echo "项目目录: ${ROOT_DIR}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --restart)
      RESTART_MODE=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "错误: 未知参数 $1"
      usage
      exit 1
      ;;
  esac
done

if [[ "${RESTART_MODE}" -eq 1 ]]; then
  echo "模式: restart"
fi

require_env "DASHSCOPE_API_KEY"
require_env "DEEPSEEK_API_KEY"
require_env "DIFY_API_KEY"

echo "检查基础设施状态..."
cd "${ROOT_DIR}"
running_containers=$(docker compose ps --status running)
for container in "${REQUIRED_CONTAINERS[@]}"; do
  if ! grep -q "${container}" <<< "${running_containers}"; then
    echo "错误: ${container} 未启动"
    exit 1
  fi
done
echo "基础设施检查通过"

mkdir -p "${LOG_DIR}"

for entry in "${SERVICES[@]}"; do
  IFS=":" read -r service port health_path <<< "${entry}"
  start_service "${service}" "${port}" "${health_path}"
done

echo ""
echo "=== 启动完成 ==="
echo "user-profile-service: http://localhost:8101"
echo "vector-memory-service: http://localhost:8102"
echo "ai-chat-service: http://localhost:8100"
echo ""
echo "日志目录: ${LOG_DIR}"
echo "查看日志: tail -f ${LOG_DIR}/*.log"
echo "停止服务: ${ROOT_DIR}/sh/run/stop-ai-services.sh"
