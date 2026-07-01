#!/bin/bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "${SCRIPT_DIR}/../.." && pwd)
LOG_DIR="${ROOT_DIR}/logs/ai-services"
RESTART_MODE=0
DEPENDENCIES_PREPARED=0
WITH_OBSERVABILITY=0

SERVICES=(
  "user-profile-service:8101:/actuator/health"
  "vector-memory-service:8102:/actuator/health"
  "ai-chat-service:8100:/api/chat/health"
  "doctor-assistant-agent:8088:/actuator/health"
)

REQUIRED_CONTAINERS=(
  "gov-mysql"
  "gov-redis"
  "gov-rabbitmq"
  "gov-nacos"
  "gov-elasticsearch"
  "gov-milvus"
)

DEFAULT_INFRA_SERVICES=(
  "mysql"
  "redis"
  "rabbitmq"
  "nacos"
  "elasticsearch"
  "etcd"
  "minio"
  "milvus"
)

OBSERVABILITY_INFRA_SERVICES=(
  "kibana"
  "logstash"
  "prometheus"
  "grafana"
  "jaeger"
)

usage() {
  cat <<'EOF'
用法:
  ./sh/run/start-ai-services.sh [--restart] [--with-observability]

参数:
  --restart              先停止并清理 8088、8100~8102 对应服务，再重新启动
  --with-observability   额外启动 Kibana / Logstash / Prometheus / Grafana / Jaeger

可选环境变量:
  MEMORY_RERANK_PROVIDER   rerank 模式，默认 embedding，可选 bge-http
  MEMORY_RERANK_BASE_URL   外部 rerank 服务地址，默认 http://localhost:9997
EOF
}

prepare_module_dependencies() {
  if [[ "${DEPENDENCIES_PREPARED}" -eq 1 ]]; then
    return 0
  fi

  echo "预编译并安装 AI 共享模块依赖到本地 Maven 仓库..."
  (
    cd "${ROOT_DIR}" && \
    mvn -q -pl common,ai/agent-platform-common -am -DskipTests install
  )
  DEPENDENCIES_PREPARED=1
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

wait_for_container_ready() {
  local container="$1"
  local timeout="${2:-180}"
  local start_ts
  start_ts=$(date +%s)

  while true; do
    local status
    status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container}" 2>/dev/null || true)

    if [[ "${status}" == "healthy" || "${status}" == "running" ]]; then
      echo "  ${container} 就绪: ${status}"
      return 0
    fi

    if [[ -z "${status}" ]]; then
      echo "  等待 ${container} 创建中..."
    else
      echo "  等待 ${container} 就绪，当前状态: ${status}"
    fi

    if (( $(date +%s) - start_ts >= timeout )); then
      echo "错误: ${container} 在 ${timeout}s 内未就绪"
      docker ps -a --filter "name=${container}" || true
      return 1
    fi

    sleep 3
  done
}

start_infra() {
  echo "启动基础设施容器（默认基础链路 + vector）..."
  docker compose up -d "${DEFAULT_INFRA_SERVICES[@]}"

  if [[ "${WITH_OBSERVABILITY}" -eq 1 ]]; then
    echo "启动可观测性容器..."
    docker compose --profile observability up -d "${OBSERVABILITY_INFRA_SERVICES[@]}"
  fi

  echo "等待核心基础设施就绪..."
  for container in "${REQUIRED_CONTAINERS[@]}"; do
    wait_for_container_ready "${container}"
  done
}

launch_service_process() {
  local log_file="$1"
  shift

  nohup python3 -c '
import os
import sys

os.setsid()
os.execvp(sys.argv[1], sys.argv[1:])
' "$@" < /dev/null > "${log_file}" 2>&1 &
}

append_profile() {
  local profiles="$1"
  local profile_to_add="$2"

  if [[ -z "${profiles}" ]]; then
    echo "${profile_to_add}"
    return 0
  fi

  if [[ ",${profiles}," == *",${profile_to_add},"* ]]; then
    echo "${profiles}"
    return 0
  fi

  echo "${profiles},${profile_to_add}"
}

start_service() {
  local service="$1"
  local port="$2"
  local health_path="$3"
  local pid_file="${LOG_DIR}/${service}.pid"
  local log_file="${LOG_DIR}/${service}.log"
  local run_args=()
  local spring_profiles="${SPRING_PROFILES_ACTIVE:-}"

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

  prepare_module_dependencies

  if [[ "${service}" == "doctor-assistant-agent" ]]; then
    run_args+=("-Dspring-boot.run.fork=false")
  fi

  if [[ "${service}" == "ai-chat-service" && "${WITH_OBSERVABILITY}" -eq 1 ]]; then
    spring_profiles=$(append_profile "${spring_profiles}" "observability")
  fi

  if [[ -n "${spring_profiles}" ]]; then
    run_args+=("-Dspring-boot.run.profiles=${spring_profiles}")
  fi

  echo "启动 ${service} (${port})..."
  if [[ "${#run_args[@]}" -gt 0 ]]; then
    launch_service_process "${log_file}" mvn -q -pl "ai/${service}" "${run_args[@]}" spring-boot:run
  else
    launch_service_process "${log_file}" mvn -q -pl "ai/${service}" spring-boot:run
  fi
  local pid=$!
  echo "${pid}" > "${pid_file}"

  if ! wait_for_health "${service}" "${port}" "${health_path}"; then
    echo "  ${service} 启动失败，最近日志如下:"
    tail -n 80 "${log_file}" || true
    exit 1
  fi
}

echo "=== 启动 AI 主链路服务 ==="
echo "项目目录: ${ROOT_DIR}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --restart)
      RESTART_MODE=1
      shift
      ;;
    --with-observability)
      WITH_OBSERVABILITY=1
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
if [[ "${WITH_OBSERVABILITY}" -eq 1 ]]; then
  echo "模式: with observability"
fi

require_env "DASHSCOPE_API_KEY"
require_env "DEEPSEEK_API_KEY"
require_env "DIFY_API_KEY"

export MEMORY_RERANK_PROVIDER="${MEMORY_RERANK_PROVIDER:-embedding}"
export MEMORY_RERANK_BASE_URL="${MEMORY_RERANK_BASE_URL:-http://localhost:9997}"

echo "Rerank provider: ${MEMORY_RERANK_PROVIDER}"
if [[ "${MEMORY_RERANK_PROVIDER}" == "bge-http" ]]; then
  echo "Rerank base URL: ${MEMORY_RERANK_BASE_URL}"
fi

echo "检查基础设施状态..."
cd "${ROOT_DIR}"
start_infra
running_containers=$(docker compose ps --status running)
for container in "${REQUIRED_CONTAINERS[@]}"; do
  if ! grep -q "${container}" <<< "${running_containers}"; then
    echo "错误: ${container} 未启动"
    exit 1
  fi
done
echo "基础设施检查通过"

if [[ "${MEMORY_RERANK_PROVIDER}" == "bge-http" ]]; then
  rerank_health_url="${MEMORY_RERANK_BASE_URL%/}/health"
  if curl -fsS "${rerank_health_url}" >/dev/null 2>&1; then
    echo "外部 rerank 服务检查通过: ${rerank_health_url}"
  else
    echo "警告: 外部 rerank 服务暂不可达: ${rerank_health_url}"
    echo "如果需要启用 BGE Rerank，请先执行: docker compose --profile rerank up -d bge-rerank-service"
  fi
fi

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
echo "doctor-assistant-agent: http://localhost:8088"
echo ""
echo "日志目录: ${LOG_DIR}"
echo "查看日志: tail -f ${LOG_DIR}/*.log"
echo "停止服务: ${ROOT_DIR}/sh/run/stop-ai-services.sh"
