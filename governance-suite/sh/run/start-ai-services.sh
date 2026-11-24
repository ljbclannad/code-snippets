#!/bin/bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "${SCRIPT_DIR}/../.." && pwd)
LOG_DIR="${ROOT_DIR}/logs/ai-services"
RESTART_MODE=0
DEPENDENCIES_PREPARED=0
WITH_OBSERVABILITY=0
ALL_SERVICES=0

SERVICES=(
  "user-profile-service:8101:/actuator/health"
  "vector-memory-service:8102:/actuator/health"
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
  ./sh/run/start-ai-services.sh [--all] [--restart] [--with-observability]

参数:
  --all                  启动全部六个 AI 服务，包含知识库和评测
  --restart              重启本次选中的服务
  --with-observability   额外启动 Kibana / Logstash / Prometheus / Grafana / Jaeger

可选环境变量:
  AI_MIDDLEWARE_HOST       中间件主机，默认 175.27.242.127；设为 localhost 可启动本地 Compose 基础设施
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
  lsof -nP -tiTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1
}

kill_port_occupants() {
  local port="$1"
  local service="$2"
  local pids

  pids=$(lsof -nP -tiTCP:"${port}" -sTCP:LISTEN 2>/dev/null || true)
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
  local pid="$4"
  local timeout=90
  local url="http://localhost:${port}${path}"
  local start_ts
  start_ts=$(date +%s)

  while true; do
    if ! kill -0 "${pid}" 2>/dev/null; then
      echo "  ${service} 启动进程已退出，请检查日志: ${LOG_DIR}/${service}.log"
      return 1
    fi
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

  echo "等待核心基础设施就绪..."
  for container in "${REQUIRED_CONTAINERS[@]}"; do
    wait_for_container_ready "${container}"
  done
}

launch_service_process() {
  local log_file="$1"
  shift

  # 独立会话让后台服务在启动终端退出后继续运行，exec 保留 PID 文件里的进程号。
  nohup perl -MPOSIX=setsid -e '
    defined(setsid()) or die "无法建立后台会话: $!";
    exec { $ARGV[0] } @ARGV or die "无法启动服务: $!";
  ' -- "$@" < /dev/null > "${log_file}" 2>&1 &
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
    echo "错误: 端口 ${port} 已被占用，无法启动 ${service}"
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

  if ! wait_for_health "${service}" "${port}" "${health_path}" "${pid}"; then
    echo "  ${service} 启动失败，最近日志如下:"
    tail -n 80 "${log_file}" || true
    exit 1
  fi
}

echo "=== 启动 AI 主链路服务 ==="
echo "项目目录: ${ROOT_DIR}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --all)
      ALL_SERVICES=1
      shift
      ;;
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

if [[ "${ALL_SERVICES}" -eq 1 ]]; then
  SERVICES+=("knowledge-base-service:8103:/actuator/health" "evaluation-service:${EVALUATION_PORT:-8104}:/api/evaluation/health")
fi
SERVICES+=("doctor-assistant-agent:8088:/actuator/health" "ai-chat-service:8100:/api/chat/health")

require_env "DASHSCOPE_API_KEY"
require_env "DEEPSEEK_API_KEY"

export MEMORY_RERANK_PROVIDER="${MEMORY_RERANK_PROVIDER:-embedding}"
export MEMORY_RERANK_BASE_URL="${MEMORY_RERANK_BASE_URL:-http://localhost:9997}"

echo "Rerank provider: ${MEMORY_RERANK_PROVIDER}"
if [[ "${MEMORY_RERANK_PROVIDER}" == "bge-http" ]]; then
  echo "Rerank base URL: ${MEMORY_RERANK_BASE_URL}"
fi

cd "${ROOT_DIR}"
export AI_MIDDLEWARE_HOST="${AI_MIDDLEWARE_HOST:-175.27.242.127}"
case "${AI_MIDDLEWARE_HOST}" in
  localhost|127.0.0.1)
    export REDIS_PORT="${REDIS_PORT:-36379}"
    export AI_MYSQL_USERNAME="${AI_MYSQL_USERNAME:-root}"
    export AI_MYSQL_PASSWORD="${AI_MYSQL_PASSWORD:-root}"
    export AI_DOCTOR_MYSQL_USERNAME="${AI_DOCTOR_MYSQL_USERNAME:-root}"
    export AI_DOCTOR_MYSQL_PASSWORD="${AI_DOCTOR_MYSQL_PASSWORD:-${MYSQL_PASSWORD:-root}}"
    export RABBITMQ_USERNAME="${RABBITMQ_USERNAME:-guest}"
    export RABBITMQ_PASSWORD="${RABBITMQ_PASSWORD:-guest}"
    start_infra
    ;;
  175.27.242.127)
    echo "使用远程中间件：${AI_MIDDLEWARE_HOST}"
    _ai_ssh_transport=ssh source "${SCRIPT_DIR}/ai-middleware-env.sh"
    ;;
  *)
    echo "使用远程中间件：${AI_MIDDLEWARE_HOST}"
    ;;
esac

if [[ "${WITH_OBSERVABILITY}" -eq 1 ]]; then
  echo "启动可观测性容器..."
  docker compose --profile observability up -d "${OBSERVABILITY_INFRA_SERVICES[@]}"
fi

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
for entry in "${SERVICES[@]}"; do
  IFS=":" read -r service port _ <<< "${entry}"
  echo "${service}: http://localhost:${port}"
done
echo ""
echo "日志目录: ${LOG_DIR}"
echo "查看日志: tail -f ${LOG_DIR}/*.log"
if [[ "${ALL_SERVICES}" -eq 1 ]]; then
  echo "停止服务: bash ${ROOT_DIR}/sh/run/stop-ai-services.sh --all"
else
  echo "停止服务: bash ${ROOT_DIR}/sh/run/stop-ai-services.sh"
fi
