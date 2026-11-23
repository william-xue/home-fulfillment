#!/bin/bash
# Sent to Bash over stdin inside the project container after MySQL becomes healthy.
set -euo pipefail
if [[ ! "${HF_DB_APP_PASSWORD:-}" =~ ^[A-Za-z0-9]{24,}$ ]]; then
  echo "HF_DB_APP_PASSWORD must contain at least 24 ASCII letters/digits" >&2
  exit 1
fi

MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=SOCKET -uroot <<SQL
CREATE USER IF NOT EXISTS 'hf_app'@'%' IDENTIFIED BY '${HF_DB_APP_PASSWORD}';
GRANT SELECT, INSERT, UPDATE ON home_fulfillment.* TO 'hf_app'@'%';
SQL

MYSQL_PWD="$HF_DB_APP_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uhf_app home_fulfillment -Nse 'SELECT 1' >/dev/null
