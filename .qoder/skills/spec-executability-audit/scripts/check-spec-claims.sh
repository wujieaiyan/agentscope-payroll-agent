#!/usr/bin/env bash
# 规格主张机械核对：file:line 引用、Java 类型、配置键。
# 用法: scripts/check-spec-claims.sh docs/xxx.md
# 退出码: 0 全部可解析；1 存在硬失败；2 用法/环境错误。
set -u
export LC_ALL=C

SPEC=${1:-}
if [ -z "$SPEC" ] || [ ! -f "$SPEC" ]; then
  echo "用法: $0 <spec.md>" >&2
  exit 2
fi

ROOT=$(git -C "$(dirname "$SPEC")" rev-parse --show-toplevel 2>/dev/null) || {
  echo "错误: 规格必须在 git 仓库内" >&2
  exit 2
}
cd "$ROOT" || exit 2
SPEC=${SPEC#"$ROOT"/}

FILES=$(git ls-files)
fail_ref=0
ok_ref=0
ref_errors=()

resolve_suffix() {
  # $1: 相对后缀（可含目录）; 输出唯一匹配的 tracked 文件，否则空
  local suffix=$1
  local matches count
  matches=$(printf '%s\n' "$FILES" | grep -F -- "/$suffix" || true)
  count=$(printf '%s\n' "$matches" | grep -c . || true)
  if [ "$count" -eq 1 ]; then
    printf '%s' "$matches"
  fi
}

line_total() {
  wc -l < "$1" | tr -d ' '
}

# ---------- 1) file:line 引用 ----------
while IFS= read -r ref; do
  [ -z "$ref" ] && continue
  path=${ref%:*}
  range=${ref##*:}
  start=${range%-*}

  resolved=""
  if [ -f "$path" ]; then
    resolved=$path
  elif printf '%s' "$path" | grep -q '/\.\.\./'; then
    resolved=$(resolve_suffix "${path##*/\.\.\./}")
  else
    base=$(basename "$path")
    candidates=$(printf '%s\n' "$FILES" | grep -F -- "/$base" || true)
    count=$(printf '%s\n' "$candidates" | grep -c . || true)
    if [ "$count" -eq 1 ]; then
      resolved=$candidates
    elif [ "$count" -gt 1 ]; then
      resolved=$(resolve_suffix "$path")
    fi
  fi

  if [ -z "$resolved" ]; then
    ref_errors+=("$ref  -> 文件无法唯一解析（请写全路径或唯一后缀）")
    fail_ref=$((fail_ref+1))
    continue
  fi

  total=$(line_total "$resolved")
  if [ "$start" -gt "$total" ] 2>/dev/null; then
    ref_errors+=("$ref  -> $resolved 仅 $total 行")
    fail_ref=$((fail_ref+1))
  else
    ok_ref=$((ok_ref+1))
  fi
done < <(grep -oE '[A-Za-z0-9_][A-Za-z0-9_./-]*\.(java|sql|ya?ml|xml|json|ts|vue|md):[0-9]+(-[0-9]+)?' "$SPEC" | sort -u)

# ---------- 2) Java 类型名 ----------
EXTERNAL=" AbortPolicy BufferedImage CompletableFuture HttpClient Hub SseEmitter SynchronousQueue HikariDataSourceMetricsBinder "
missing_types=()
new_types=()
while IFS= read -r name; do
  [ -z "$name" ] && continue
  case "$EXTERNAL" in
    *" $name "*) continue ;;
  esac
  if printf '%s\n' "$FILES" | grep -qF -- "/$name.java"; then
    continue
  fi
  if grep -qE "(新增|独立为|抽成|下沉|嵌套类型|新模块)" <(grep -w -- "$name" "$SPEC"); then
    new_types+=("$name")
  elif grep -qF "**\`$name\`**" "$SPEC"; then
    new_types+=("$name")
  else
    missing_types+=("$name")
  fi
done < <(grep -oE '`[A-Z][A-Za-z0-9]{2,}`' "$SPEC" | tr -d '`' | grep -E '[a-z]' | sort -u)

# ---------- 3) payroll.* 配置键 ----------
CONFIG_NS=" auth audit chat deployment guard internal jobs kb runtime security team usage "
METRIC_TAIL=" duration failures gap rejected timeout connections subscribers "
declared=$(sed -n '/^## 6\./,/^## 7\./p' "$SPEC" | grep -oE 'payroll\.[a-z0-9]+(\.[a-z0-9-]+)+' | sort -u)
keys_existing=0
keys_undeclared=()
while IFS= read -r key; do
  [ -z "$key" ] && continue
  ns=$(printf '%s' "$key" | cut -d. -f2)
  tail=${key##*.}
  case "$CONFIG_NS" in
    *" $ns "*) ;;
    *) continue ;;
  esac
  case "$METRIC_TAIL" in
    *" $tail "*) continue ;;
  esac
  if printf '%s\n' "$declared" | grep -qxF -- "$key"; then
    continue
  fi
  if grep -rIqF --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=target \
      --exclude-dir=dist --exclude='*.md' -- "$key" \
      payroll-common/src payroll-agent/src payroll-backend/src payroll-sandbox/src \
      payroll-tests/src docker frontend/src 2>/dev/null; then
    keys_existing=$((keys_existing+1))
  else
    keys_undeclared+=("$key")
  fi
done < <(grep -oE 'payroll\.[a-z0-9]+(\.[a-z0-9-]+)+' "$SPEC" | sort -u)

# ---------- 汇总 ----------
echo "== 规格主张核对 =="
echo "文件: $SPEC"
echo "file:line 引用: 通过 $ok_ref / 失败 $fail_ref"
echo "类型名: 新建候选 ${#new_types[@]} / 缺失 ${#missing_types[@]}"
echo "配置键: 既有 $keys_existing / 未登记且不存在 ${#keys_undeclared[@]}"

if [ "$fail_ref" -gt 0 ]; then
  echo
  echo "[引用失败]"
  printf '  %s\n' "${ref_errors[@]}" | head -50
fi
if [ "${#missing_types[@]}" -gt 0 ]; then
  echo
  echo "[类型缺失] 被当作既有类型引用，但仓库无对应文件"
  printf '  %s\n' "${missing_types[@]}"
fi
if [ "${#new_types[@]}" -gt 0 ]; then
  echo
  echo "[新建/下沉候选] 需确认已进入改动清单"
  printf '  %s\n' "${new_types[@]}"
fi
if [ "${#keys_undeclared[@]}" -gt 0 ]; then
  echo
  echo "[配置键失败] 未在 §6 登记，且代码中不存在"
  printf '  %s\n' "${keys_undeclared[@]}"
fi

if [ "$fail_ref" -gt 0 ] || [ "${#missing_types[@]}" -gt 0 ] || [ "${#keys_undeclared[@]}" -gt 0 ]; then
  exit 1
fi
echo "结果: 通过（新建候选仍需人工确认）"
exit 0
