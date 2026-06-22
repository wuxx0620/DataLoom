#!/usr/bin/env bash
# 将 DataLoom 功能分支推送到 Gitee。
# 用法：
#   export GITEE_REPO=https://gitee.com/你的用户名/DataLoom.git
#   bash scripts/push-to-gitee.sh
#
# 若 origin 已指向 Gitee，可省略 GITEE_REPO：
#   bash scripts/push-to-gitee.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

GITEE_REPO="${GITEE_REPO:-}"

if [[ -n "${GITEE_REPO}" ]]; then
  if git remote get-url gitee >/dev/null 2>&1; then
    git remote set-url gitee "${GITEE_REPO}"
  else
    git remote add gitee "${GITEE_REPO}"
  fi
  REMOTE=gitee
else
  ORIGIN_URL="$(git remote get-url origin 2>/dev/null || true)"
  if [[ "${ORIGIN_URL}" == *"gitee.com"* ]]; then
    REMOTE=origin
  else
    echo "请设置 Gitee 仓库地址，例如："
    echo "  export GITEE_REPO=https://gitee.com/你的用户名/DataLoom.git"
    echo "  bash scripts/push-to-gitee.sh"
    exit 1
  fi
fi

echo "==> 推送到 ${REMOTE} ($(git remote get-url "${REMOTE}"))"

BRANCHES=(
  feature/fliex-develop
  cursor/tech-stack-upgrade-plan-1d38
  cursor/wave0-baseline-1d38
  cursor/fix-formula-recalc-1d38
)

for branch in "${BRANCHES[@]}"; do
  if git show-ref --verify --quiet "refs/heads/${branch}"; then
    echo "-- push ${branch}"
    git push -u "${REMOTE}" "${branch}"
  else
    echo "-- skip ${branch} (本地不存在)"
  fi
done

if git show-ref --verify --quiet refs/tags/baseline-v2.0.0-pre-upgrade; then
  echo "-- push tag baseline-v2.0.0-pre-upgrade"
  git push "${REMOTE}" baseline-v2.0.0-pre-upgrade
fi

echo "完成。"
