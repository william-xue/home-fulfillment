#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
root="$PWD"
case "${1:-}" in
  ''|--app-only) ;;
  *) echo '用法：./stop.sh [--app-only]' >&2; exit 2 ;;
esac

stop_process() {
  local name="$1" needle="$2" pidfile=".run/$1.pid" pid command
  [[ -f "$pidfile" ]] || return 0
  read -r pid < "$pidfile"
  if [[ ! "$pid" =~ ^[1-9][0-9]*$ ]]; then
    echo "忽略无效 PID 文件：$pidfile" >&2
    return 1
  fi
  command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  if [[ -n "$command" && "$command" != *"$needle"* ]]; then
    echo "$pidfile 对应的进程不属于本项目，不停止。" >&2
    return 1
  fi
  if [[ -n "$command" ]]; then
    kill "$pid" 2>/dev/null || true
    for ((attempt=0; attempt<15; attempt++)); do
      [[ "$(ps -p "$pid" -o command= 2>/dev/null || true)" == *"$needle"* ]] || break
      sleep 1
    done
    if [[ "$(ps -p "$pid" -o command= 2>/dev/null || true)" == *"$needle"* ]]; then
      echo "$name 尚未结束：PID $pid" >&2
      return 1
    fi
  fi
  rm -f -- "$pidfile"
}

stop_process frontend "$root/frontend/node_modules/vite/bin/vite.js"
stop_process backend "$root/backend/target/home-fulfillment.jar"
if [[ "${1:-}" != --app-only && -f .env ]]; then
  docker compose --profile m4 stop
fi
