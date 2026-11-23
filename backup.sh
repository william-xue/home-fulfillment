#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
[[ $# -le 1 ]] || { echo '用法：bash backup.sh [输出 SQL 路径]' >&2; exit 2; }
umask 077
mkdir -p .run/backups
destination="${1:-.run/backups/home-fulfillment-$(date '+%Y%m%d-%H%M%S').sql}"
[[ ! -e "$destination" && ! -L "$destination" ]] || { echo '目标文件已存在，拒绝覆盖。' >&2; exit 2; }
temp="$(mktemp "${destination}.partial.XXXXXXXX")"
trap 'rm -f -- "$temp"' EXIT
docker compose exec -T mysql sh -ec \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF --databases home_fulfillment' \
  > "$temp"
grep -q '^-- Dump completed' "$temp"
ln -- "$temp" "$destination"
printf '备份已写入 %s\n' "$destination"
