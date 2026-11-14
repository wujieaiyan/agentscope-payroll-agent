#!/usr/bin/env bash
# 动态智能体团队（ACTIVE）最小可运行 Demo
#
# 前置条件：
#   1. Web(8080)、runtime(8100)、Nacos(8848)、Ollama(11434) 已启动
#   2. runtime 以 PAYROLL_TEAM_MODE=ACTIVE 启动（构造期读取，改环境变量必须重启）
#   3. 账号具备 FLOW_MANAGE / AGENT_MANAGE 权限（管理员）
#   4. Nacos 目录 payroll-agents.json 中至少有 ONLINE + PUBLIC 的薪酬计算/复核智能体
#
# 关键点：全局默认流程生效时，对话固定走 FLOW，路由与动态团队都不会执行。
# 本脚本会临时取消默认流程，退出时自动恢复；runtime 侧流程配置有 60s 缓存，
# 取消后默认等待 PAYROLL_DEMO_FLOW_WAIT 秒再提问。
#
# 用法：
#   PAYROLL_USER=<管理员> PAYROLL_PASSWORD=<密码> \
#     bash scripts/demo-team.sh "把 EMP001 2026 年 10 月的工资核算拆成计算和核对两步，交给多个智能体协作完成"
#
# 可选环境变量：
#   PAYROLL_WEB=http://127.0.0.1:8080
#   PAYROLL_DEMO_FLOW_WAIT=62   # 0 表示不等待（流程本来是空的也无所谓）
set -euo pipefail

WEB="${PAYROLL_WEB:-http://127.0.0.1:8080}"
USER_NAME="${PAYROLL_USER:-}"
PASSWORD="${PAYROLL_PASSWORD:-}"
QUESTION="${*:-把 EMP001 2026 年 10 月的工资核算拆成计算和核对两步，交给多个智能体协作完成}"

if [ -z "$USER_NAME" ] || [ -z "$PASSWORD" ]; then
    echo "用法: PAYROLL_USER=<管理员> PAYROLL_PASSWORD=<密码> bash scripts/demo-team.sh \"<问题>\"" >&2
    exit 2
fi
if ! command -v node >/dev/null 2>&1; then
    echo "需要 node 解析 JSON（前端构建已依赖 Node）" >&2
    exit 2
fi

TMP="$(mktemp -d)"
COOKIE="$TMP/cookie.txt"
RESP="$TMP/resp.json"
REQ="$TMP/req.json"
RESTORE_FLOW=""

cat > "$TMP/get.js" <<'JS'
const fs = require('fs');
const [file, path] = process.argv.slice(2);
let value = JSON.parse(fs.readFileSync(file, 'utf8'));
for (const key of path ? path.split('.') : []) {
  value = value == null ? value : value[key];
}
if (value === undefined || value === null) process.exit(0);
console.log(typeof value === 'object' ? JSON.stringify(value) : value);
JS

cat > "$TMP/show.js" <<'JS'
const fs = require('fs');
const [file, path] = process.argv.slice(2);
let value = JSON.parse(fs.readFileSync(file, 'utf8'));
for (const key of path ? path.split('.') : []) {
  value = value == null ? value : value[key];
}
console.log(JSON.stringify(value, null, 2));
JS

cat > "$TMP/body.js" <<'JS'
const [keys] = process.argv.slice(2);
const values = keys.split(',').map((key) => process.env['DEMO_' + key] || '');
console.log(JSON.stringify(Object.fromEntries(keys.split(',').map((key, i) => [key, values[i]]))));
JS

jget() { node "$TMP/get.js" "$RESP" "${1:-}"; }
show() { node "$TMP/show.js" "$RESP" "${1:-}"; }
# 用环境变量传值，避免中文/引号被 shell 或 curl 二次解析
body_of() { env "DEMO_$1=$2" node "$TMP/body.js" "$1"; }
login_body() {
    env DEMO_username="$USER_NAME" DEMO_password="$PASSWORD" \
        node "$TMP/body.js" "username,password"
}

cleanup() {
    if [ -n "$RESTORE_FLOW" ]; then
        echo
        echo "恢复全局默认流程：$RESTORE_FLOW"
        curl -sS -b "$COOKIE" -X PUT "$WEB/api/admin/flows/$RESTORE_FLOW/active" -o /dev/null \
            || echo "  恢复失败，请到「流程编排」页手动设为默认" >&2
    fi
    rm -rf "$TMP"
}
trap cleanup EXIT

request() { # METHOD PATH [BODY]
    local method="$1" path="$2" body="${3-}"
    local args=(-sS --max-time 300 -b "$COOKIE" -c "$COOKIE" -o "$RESP" -w '%{http_code}'
        -X "$method" "$WEB$path")
    if [ -n "$body" ]; then
        printf '%s' "$body" > "$REQ"
        args+=(-H 'Content-Type: application/json' --data-binary "@$REQ")
    fi
    local code
    code="$(curl "${args[@]}")" || { echo "请求失败：$method $path" >&2; exit 1; }
    local status
    status="$(jget status || true)"
    if [ "$code" -ge 400 ] || [ "$status" != "OK" ]; then
        echo "请求失败：$method $path（HTTP $code）" >&2
        jget message >&2 || true
        exit 1
    fi
}

echo "== 1. 检查服务 =="
curl -sS --max-time 10 "$WEB/api/health" -o "$RESP" || { echo "Web 不可达：$WEB" >&2; exit 1; }
echo "chat_mode=$(jget data.chat_mode)  chat_flow=$(jget data.chat_flow)  model=$(jget data.chat_model)"

echo
echo "== 2. 登录 =="
request POST /api/auth/login "$(login_body)"
echo "登录成功：$(jget data.user.username)（$(jget data.user.role_code)）"

echo
echo "== 3. 团队配置 =="
request GET /api/admin/team/config
echo "mode=$(jget data.mode)  enabled=$(jget data.enabled)  activeEnabled=$(jget data.active_enabled)"
echo "白名单：用户全放行=$(jget data.active_all_users)  会话全放行=$(jget data.active_all_sessions)"
if [ "$(jget data.mode)" != "ACTIVE" ]; then
    echo "！当前不是 ACTIVE：需在 runtime 启动参数设置 PAYROLL_TEAM_MODE=ACTIVE 并重启" >&2
    exit 1
fi
request GET /api/admin/agents/active
CONFIGURED_AGENT="$(jget data.configured || true)"
if [ -n "$CONFIGURED_AGENT" ]; then
    echo "！已配置默认智能体 $CONFIGURED_AGENT：只有路由明确选择 team 时才会组队"
fi

echo
echo "== 4. 默认流程（有则临时取消）=="
request GET /api/admin/flows
ACTIVE_FLOW="$(jget data.activeFlowId || true)"
if [ -n "$ACTIVE_FLOW" ]; then
    echo "当前默认流程：$ACTIVE_FLOW → 固定走 FLOW，动态团队不会执行，临时取消"
    request DELETE /api/admin/flows/active
    RESTORE_FLOW="$ACTIVE_FLOW"
    WAIT="${PAYROLL_DEMO_FLOW_WAIT:-62}"
    if [ "$WAIT" -gt 0 ]; then
        echo "等待 ${WAIT}s 让 runtime 的流程缓存（60s TTL）失效…"
        sleep "$WAIT"
    fi
else
    echo "未设置默认流程，直接进入智能体/团队链路"
fi

echo
echo "== 5. 路由预览（只调轻量模型，不执行回答）=="
request POST /api/agents/route-preview "$(body_of message "$QUESTION")"
ROUTE_TARGET="$(jget data.target || true)"
echo "target=${ROUTE_TARGET:-（空）}  confidence=$(jget data.confidence)  team_available=$(jget data.team_available)"
echo "reason=$(jget data.reason)"
FAILURE="$(jget data.failure_reason || true)"
if [ -n "$FAILURE" ]; then
    echo "failure_reason=$FAILURE"
fi
if [ "$(jget data.team_available || true)" != "true" ]; then
    echo "！团队不可用：确认 runtime 启动日志「[动态团队] 模式：ACTIVE」及白名单" >&2
    exit 1
fi
ROUTER_ENABLED="$(jget data.enabled || true)"
if [ "$ROUTER_ENABLED" != "true" ]; then
    echo "路由未启用（PAYROLL_ROUTER_ENABLED=false）：跳过路由判断，消息会直接进入团队门禁"
elif [ "$ROUTE_TARGET" != "team" ]; then
    echo "！本次路由没有选择团队（模型判为 ${ROUTE_TARGET:-空}），提问不会进入 ACTIVE 链路。" >&2
    echo "  可换更明确的团队型问题重试，例如：把 EMP001 2026 年 10 月的工资核算拆成计算和核对两步，交给多个智能体协作完成" >&2
    echo "  或重启 runtime 时设置 PAYROLL_ROUTER_ENABLED=false，让消息直接进入团队门禁。" >&2
    exit 1
fi

echo
echo "== 6. 新建会话并提问（同步接口，内部仍在 runtime 执行）=="
request POST /api/chat/sessions '{"title":"动态团队 Demo"}'
SESSION_ID="$(jget data.id)"
echo "sessionId=$SESSION_ID"
echo "问题：$QUESTION"
request POST "/api/chat/sessions/$SESSION_ID/messages" "$(body_of message "$QUESTION")"
ROUTED_PATH="$(jget data.routing.path || true)"
echo "路由路径：${ROUTED_PATH:-（空）}  persona=$(jget data.routing.persona)"
# 同步接口的 Reply 不带 team 对象，团队运行 id 在 routing.team_run_id 上
echo "团队运行：runId=$(jget data.routing.team_run_id)"
echo "智能体：$(jget data.routing.agent_ids)"
REPLY="$(jget data.reply || true)"
echo "答复片段：$(DEMO_VALUE="$REPLY" node -e 'process.stdout.write((process.env.DEMO_VALUE || "").slice(0, 160))')"
if [ "$ROUTED_PATH" != "TEAM_ACTIVE" ]; then
    echo "！本轮没有走动态团队：path=${ROUTED_PATH:-空}" >&2
    echo "  FLOW 说明 runtime 仍在用默认流程缓存；PIPELINE/AGENT 说明路由或团队门禁未命中，" >&2
    echo "  查看 runtime 日志中「[动态团队] ACTIVE 组队回退：原因=L0/L2/L3」定位具体门槛。" >&2
    exit 1
fi

echo
echo "== 7. 核对落库结果 =="
request GET "/api/chat/sessions/$SESSION_ID/messages"
MESSAGE_ID="$(node -e '
const fs = require("fs");
const rows = JSON.parse(fs.readFileSync(process.argv[1], "utf8")).data || [];
const hit = [...rows].reverse().find((row) => row.role === "ASSISTANT");
console.log(hit ? hit.id : "");
' "$RESP")"
if [ -z "$MESSAGE_ID" ]; then
    echo "未找到助手消息，后续核对跳过" >&2
    exit 1
fi
echo "助手消息 id=$MESSAGE_ID"
request GET "/api/chat/sessions/$SESSION_ID/messages/$MESSAGE_ID/routing"
echo "--- routing ---"
show data
request GET "/api/chat/sessions/$SESSION_ID/messages/$MESSAGE_ID/team"
echo "--- team ---"
show data

echo
echo "== 8. 最近 ACTIVE 运行 =="
request GET "/api/admin/team/runs?days=1&mode=ACTIVE&limit=5"
show data

echo
echo "完成：如果 routing.path=TEAM_ACTIVE 且 team.status=SUCCESS，说明本轮回答由动态团队产生。"
echo "同一条消息也可在聊天页消息下方「团队运行」面板直接查看。"
