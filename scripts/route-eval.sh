#!/usr/bin/env bash
# P3 路由回归入口（规格 8.2）：本地 Web 需已启动且关闭登录（payroll.auth.enabled=false）。
#
# 用法：
#   bash scripts/route-eval.sh                     # 全量 150 条
#   bash scripts/route-eval.sh --limit 20          # 抽样冒烟
#   bash scripts/route-eval.sh --gate-only         # 只校验评测集结构
#   WEB_BASE=http://127.0.0.1:8081 bash scripts/route-eval.sh
set -euo pipefail
cd "$(dirname "$0")/.."
exec node scripts/route-eval.mjs "$@"
