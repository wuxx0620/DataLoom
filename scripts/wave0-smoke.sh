#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:9191}"
TIMEOUT="${TIMEOUT:-10}"

echo "==> Wave 0 smoke check against ${BASE_URL}"

check_endpoint() {
  local path="$1"
  local label="$2"
  local code
  code=$(curl -s -o /tmp/wave0-body.json -w "%{http_code}" --max-time "${TIMEOUT}" "${BASE_URL}${path}" || true)

  if [[ "${code}" != "200" ]]; then
    echo "FAIL: ${label} (${path}) returned HTTP ${code}"
    if [[ "${code}" == "000" ]]; then
      echo
      echo "HTTP 000 表示无法连接到 ${BASE_URL}，通常原因："
      echo "  1. 后端未启动（请先运行: cd dataloom-server && mvn spring-boot:run）"
      echo "  2. 端口不是 9191（可设置: BASE_URL=http://localhost:端口 bash scripts/wave0-smoke.sh）"
      echo "  3. 防火墙或 Docker 网络隔离"
      echo
    fi
    cat /tmp/wave0-body.json 2>/dev/null || true
    exit 1
  fi

  echo "OK: ${label} (${path})"
}

check_endpoint "/api/excel/document/list?pageNum=1&pageSize=10" "document list"

if command -v python3 >/dev/null 2>&1; then
  python3 - <<'PY'
import json, sys
with open('/tmp/wave0-body.json', 'r', encoding='utf-8') as f:
    payload = json.load(f)
if not payload.get('success'):
    print('FAIL: list API success=false', payload.get('message'))
    sys.exit(1)
print('OK: list API success=true')
PY
else
  echo "WARN: python3 not found, skipped JSON validation"
fi

echo "Wave 0 smoke check passed."
