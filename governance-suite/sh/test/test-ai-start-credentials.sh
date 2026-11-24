#!/bin/bash
# 在独立目录验证启动脚本传给 Maven 的凭据，不连接真实服务。
set -eu

root=$(cd "$(dirname "$0")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/sh/run" "$work/bin"
cp "$root/sh/run/start-ai-services.sh" "$root/sh/run/ai-middleware-env.sh" "$work/sh/run/"
cat > "$work/bin/ssh" <<'SH'
#!/bin/bash
[[ ${SSH_FAIL:-0} == 0 ]] || exit 1
printf '%s\n' GOVERNANCE_DB_PASSWORD=db DOCTOR_DB_PASSWORD=doctor REDIS_PASSWORD=remote 'RABBITMQ_PASSWORD=r=b$!'
SH
cat > "$work/bin/mvn" <<'SH'
#!/bin/bash
[[ ${GOVERNANCE_DB_PASSWORD:-} == db && ${DOCTOR_DB_PASSWORD:-} == doctor &&
   ${REDIS_PASSWORD:-} == local && ${RABBITMQ_PASSWORD:-} == 'r=b$!' ]] || exit 21
if [[ ${CHECK_LAUNCH:-0} == 1 ]]; then
  case "$*" in
    *spring-boot:run*)
      [[ $(ps -o pgid= -p "$$" | tr -d ' ') == "$$" ]] || exit 23
      touch "$PWD/maven-launched"; exit 1 ;;
    *) exit 0 ;;
  esac
fi
touch "$PWD/credentials-ok"
exit 22
SH
printf '#!/bin/bash\nexit 1\n' > "$work/bin/lsof"
printf '#!/bin/bash\nexit 1\n' > "$work/bin/curl"
printf '#!/bin/bash\nexit 126\n' > "$work/bin/python3"
chmod +x "$work/bin/"*
run() {
  env -i PATH="$work/bin:$PATH" HOME="$HOME" DASHSCOPE_API_KEY=test DEEPSEEK_API_KEY=test REDIS_PASSWORD=local "$@" \
    bash "$work/sh/run/start-ai-services.sh" --all > "$work/run.log" 2>&1
}
# 成功补全时到达 Maven，已有 Redis 密码优先。
status=0
run || status=$?
[[ $status == 22 && -f "$work/credentials-ok" ]]
if run CHECK_LAUNCH=1; then exit 1; fi
[[ -f "$work/maven-launched" ]]
grep -q '启动进程已退出' "$work/run.log"
if grep -Fq 'r=b$!' "$work/run.log"; then exit 1; fi
# SSH 失败时应在 Maven 前终止。
rm "$work/credentials-ok"
if run SSH_FAIL=1; then exit 1; fi
[[ ! -f "$work/credentials-ok" ]]
grep -q SSH "$work/run.log"
# 环境变量齐全时，即使 SSH 不可用也能启动。
status=0
run SSH_FAIL=1 GOVERNANCE_DB_PASSWORD=db DOCTOR_DB_PASSWORD=doctor 'RABBITMQ_PASSWORD=r=b$!' || status=$?
[[ $status == 22 && -f "$work/credentials-ok" ]]
