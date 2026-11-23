#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
root="$PWD"
run="$root/.run"
seed=false
m4=false
for arg in "$@"; do
  case "$arg" in
    --seed) seed=true ;;
    --m4) m4=true ;;
    *) echo '用法：./start.sh [--seed] [--m4]' >&2; exit 2 ;;
  esac
done

for command in docker java npm node curl openssl lsof; do
  command -v "$command" >/dev/null || { echo "缺少 $command，请按 README 检查环境" >&2; exit 1; }
done

if ! docker info >/dev/null 2>&1; then
  echo 'Docker daemon 不可连接。请启动 Docker Desktop，确认 docker info 成功后重试；不会改用旧 MySQL。' >&2
  exit 1
fi

if [[ ! -e .env ]]; then
  (
    umask 077
    set -C
    root_password="$(openssl rand -hex 24)"
    migration_password="$(openssl rand -hex 24)"
    app_password="$(openssl rand -hex 24)"
    {
      printf 'MYSQL_ROOT_PASSWORD=%s\n' "$root_password"
      printf 'MYSQL_PASSWORD=%s\n' "$migration_password"
      printf 'HF_DB_APP_PASSWORD=%s\n' "$app_password"
    } > .env
  )
fi

set -a
source ./.env
set +a
export HF_BACKEND_PORT="${HF_BACKEND_PORT:-8090}"
export HF_FRONTEND_PORT="${HF_FRONTEND_PORT:-5180}"
export HF_MYSQL_PORT="${HF_MYSQL_PORT:-43307}"
for port in "$HF_BACKEND_PORT" "$HF_FRONTEND_PORT" "$HF_MYSQL_PORT"; do
  [[ "$port" =~ ^[0-9]{1,5}$ ]] && (( 10#$port >= 1024 && 10#$port <= 65535 )) || {
    echo "端口必须在 1024–65535：$port" >&2
    exit 1
  }
done
if [[ "$HF_BACKEND_PORT" == "$HF_FRONTEND_PORT" || "$HF_BACKEND_PORT" == "$HF_MYSQL_PORT" || "$HF_FRONTEND_PORT" == "$HF_MYSQL_PORT" ]]; then
  echo '前端、后端和 MySQL 必须使用不同端口' >&2
  exit 1
fi
for port in "$HF_BACKEND_PORT" "$HF_FRONTEND_PORT"; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN -t >/dev/null; then
    echo "端口 $port 已被占用。若为本项目，先 ./stop.sh --app-only；不会结束其他服务。" >&2
    exit 1
  fi
done
if [[ "$seed" == true && -z "${HF_DEMO_PASSWORD:-}" ]]; then
  demo_password="$(openssl rand -hex 16)"
  printf 'HF_DEMO_PASSWORD=%s\n' "$demo_password" >> .env
  export HF_DEMO_PASSWORD="$demo_password"
fi
if [[ ! "${HF_DB_APP_PASSWORD:-}" =~ ^[A-Za-z0-9]{24,}$ ]]; then
  echo 'HF_DB_APP_PASSWORD 需至少 24 位 ASCII 字母或数字，不要替换已初始化卷的密码。' >&2
  exit 1
fi

docker compose config --quiet
(
  unset MYSQL_ROOT_PASSWORD MYSQL_PASSWORD HF_DB_APP_PASSWORD HF_DEMO_PASSWORD
  cd backend && ./mvnw -B -DskipTests package
)
(
  unset MYSQL_ROOT_PASSWORD MYSQL_PASSWORD HF_DB_APP_PASSWORD HF_DEMO_PASSWORD
  cd frontend && npm ci --no-audit --no-fund
)
docker compose up -d --wait --wait-timeout 90 mysql
docker compose exec -T mysql bash -s < infra/mysql/ensure-app-user.sh
if [[ "$m4" == true ]]; then
  if ! docker compose --profile m4 up -d --wait --wait-timeout 75 mq-broker; then
    echo 'RocketMQ 未就绪，业务仍启动；出库事件留在 MySQL，待 Broker 恢复后投递。' >&2
  fi
  if ! docker compose --profile m4 up -d --wait --wait-timeout 45 redis; then
    echo 'Redis 未就绪，运营汇总仍可查询 MySQL。' >&2
  fi
fi

mkdir -p "$run"
trap 'echo "启动未完成，请看 .run 日志；保留 MySQL 卷。" >&2; ./stop.sh --app-only || true' ERR
trap './stop.sh --app-only || true; exit 130' INT TERM
wait_for_health() {
  local url="$1" pid="$2" log="$3"
  for ((attempt=0; attempt<45; attempt++)); do
    if curl -fsS --max-time 2 "$url" 2>/dev/null | grep -q '"status":"UP"'; then
      return 0
    fi
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  echo "健康检查失败：$url（日志 $log）" >&2
  return 1
}

HF_SEED="$seed" HF_EVENTS_ENABLED="$m4" HF_OPERATIONS_CACHE="$m4" env -u MYSQL_ROOT_PASSWORD \
  nohup java "-Drocketmq.log.root=$run/rocketmq" -jar "$root/backend/target/home-fulfillment.jar" > "$run/backend.log" 2>&1 < /dev/null &
backend_pid=$!
echo "$backend_pid" > "$run/backend.pid"
wait_for_health "http://127.0.0.1:$HF_BACKEND_PORT/actuator/health/readiness" "$backend_pid" "$run/backend.log"

(
  unset MYSQL_ROOT_PASSWORD MYSQL_PASSWORD HF_DB_APP_PASSWORD
  if [[ "$seed" != true ]]; then unset HF_DEMO_PASSWORD; fi
  export HF_SEED="$seed"
  cd frontend
  exec nohup node "$root/frontend/node_modules/vite/bin/vite.js"
) > "$run/frontend.log" 2>&1 < /dev/null &
frontend_pid=$!
echo "$frontend_pid" > "$run/frontend.pid"
wait_for_health "http://127.0.0.1:$HF_FRONTEND_PORT/api/health" "$frontend_pid" "$run/frontend.log"
trap - ERR INT TERM
printf '工作台：http://127.0.0.1:%s/\n后端健康：http://127.0.0.1:%s/actuator/health\n' "$HF_FRONTEND_PORT" "$HF_BACKEND_PORT"
