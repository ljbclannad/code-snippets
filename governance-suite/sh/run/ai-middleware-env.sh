#!/bin/bash
# IDEA 在当前 shell 中加载此文件，以便导出的变量进入目标 JVM。
load_server_credentials() {
  local transport="$1" missing=() key line settings pattern
  [[ -n ${SPRING_DATASOURCE_PASSWORD:-${AI_MYSQL_PASSWORD:-${GOVERNANCE_DB_PASSWORD:-}}} ]] || missing+=(GOVERNANCE_DB_PASSWORD)
  [[ -n ${SPRING_DATASOURCE_PASSWORD:-${AI_DOCTOR_MYSQL_PASSWORD:-${DOCTOR_DB_PASSWORD:-${MYSQL_PASSWORD:-}}}} ]] || missing+=(DOCTOR_DB_PASSWORD)
  [[ -n ${SPRING_DATA_REDIS_PASSWORD:-${REDIS_PASSWORD:-}} ]] || missing+=(REDIS_PASSWORD)
  [[ -n ${SPRING_RABBITMQ_PASSWORD:-${RABBITMQ_PASSWORD:-}} ]] || missing+=(RABBITMQ_PASSWORD)
  if [[ ${#missing[@]} -eq 0 ]]; then return 0; fi

  # 只读取当前部署 .env 中缺少的应用密码，按字面值解析。
  pattern=$(IFS='|'; echo "${missing[*]}")
  echo "通过 SSH 加载中间件凭据：${missing[*]}。"
  if ! settings=$("$transport" -o ConnectTimeout=8 ubuntu@175.27.242.127 \
      "grep -E '^($pattern)=' /home/ubuntu/governance-ai/.env"); then
    echo "无法通过 SSH 加载凭据，请检查 SSH 登录或先导出 ${missing[*]}。" >&2
    return 1
  fi
  while IFS= read -r line; do
    key=${line%%=*}
    case " ${missing[*]} " in
      *" $key "*) export "$line" ;;
    esac
  done <<< "$settings"
  for key in "${missing[@]}"; do
    if [[ -z $(printenv "$key") ]]; then
      echo "服务器缺少有效的 $key，请检查部署配置。" >&2
      return 1
    fi
  done
}

if [[ ${AI_MIDDLEWARE_HOST:-175.27.242.127} == 175.27.242.127 ]]; then
  if [[ -n ${ZSH_VERSION:-} ]]; then
    _ai_env_path=${(%):-%x}
  else
    _ai_env_path=${BASH_SOURCE[0]}
  fi
  load_server_credentials "${_ai_ssh_transport:-$(dirname "$_ai_env_path")/idea-debug-ssh}" || exit 1
  unset _ai_env_path
fi
