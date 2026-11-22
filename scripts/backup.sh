#!/usr/bin/env bash
# 备份 MySQL（或 H2 文件）与 Nacos 配置，默认保留 7 天。
#
# 安全约定：
# - 数据库密码只通过 MYSQL_PWD 环境变量传给 mysqldump，不出现在命令行参数与日志中；
# - Nacos 账号密码通过 curl 的 --data-binary @- 从标准输入提交，同样不出现在 argv；
# - 脚本不打印任何密码，只打印备份路径、条目数量与恢复入口。
#
# 用法：
#   PAYROLL_DB_USER=payroll PAYROLL_DB_PASSWORD=*** ./scripts/backup.sh
#   BACKUP_DIR=/data/backups RETENTION_DAYS=7 ./scripts/backup.sh
#
# 可选环境变量：
#   PAYROLL_DB_URL        jdbc:mysql://host:3306/payroll?...，用于推导 host/port/db
#   MYSQL_HOST/MYSQL_PORT/MYSQL_DATABASE  覆盖 URL 推导结果
#   H2_FILE               设置后改为复制该 H2 文件（例如 data/payroll.mv.db），跳过 mysqldump
#   NACOS_SERVER          默认 http://127.0.0.1:8848（可带 /nacos）
#   NACOS_GROUP           默认 payroll-agent
#   NACOS_NAMESPACE       默认空（public）
#   NACOS_USERNAME/NACOS_PASSWORD  仅在开启鉴权时需要
#   SKIP_NACOS            设为 1 时跳过 Nacos 导出
#
# Nacos 使用 v1 导出接口（export=true）把分组内全部配置打包成一个 zip，
# 恢复时按 zip 内目录逐个 dataId 导入，步骤见 docs/restore-runbook.md。

set -euo pipefail

BACKUP_DIR=${BACKUP_DIR:-./backups}
RETENTION_DAYS=${RETENTION_DAYS:-7}
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
TARGET_DIR="$BACKUP_DIR/payroll-backup-$TIMESTAMP"

log() { printf '[backup] %s\n' "$*"; }
fail() {
    printf '[backup][ERROR] %s\n' "$*" >&2
    exit 1
}

case "$BACKUP_DIR" in
"" | "/" | "." | "..") fail "BACKUP_DIR 不安全：$BACKUP_DIR" ;;
esac
mkdir -p "$TARGET_DIR"

# ---------------------------------------------------------------- MySQL / H2
if [ -n "${H2_FILE:-}" ]; then
    [ -f "$H2_FILE" ] || fail "H2 文件不存在：$H2_FILE"
    cp "$H2_FILE" "$TARGET_DIR/"
    log "已复制 H2 数据库：$(basename "$H2_FILE")"
else
    DB_URL=${PAYROLL_DB_URL:-}
    MYSQL_HOST=${MYSQL_HOST:-}
    MYSQL_PORT=${MYSQL_PORT:-}
    MYSQL_DATABASE=${MYSQL_DATABASE:-}
    if [ -z "$MYSQL_HOST" ] && [[ "$DB_URL" =~ ^jdbc:mysql://([^/:?]+)(:([0-9]+))?/([^?]+) ]]; then
        MYSQL_HOST=${BASH_REMATCH[1]}
        MYSQL_PORT=${BASH_REMATCH[3]:-3306}
        MYSQL_DATABASE=${BASH_REMATCH[4]}
    fi
    MYSQL_HOST=${MYSQL_HOST:-127.0.0.1}
    MYSQL_PORT=${MYSQL_PORT:-3306}
    [ -n "$MYSQL_DATABASE" ] || fail "缺少 MYSQL_DATABASE（或提供可解析的 PAYROLL_DB_URL）"
    [ -n "${PAYROLL_DB_USER:-}" ] || fail "缺少 PAYROLL_DB_USER"
    [ -n "${PAYROLL_DB_PASSWORD:-}" ] || fail "缺少 PAYROLL_DB_PASSWORD"
    command -v mysqldump >/dev/null 2>&1 || fail "未找到 mysqldump"

    DUMP_FILE="$TARGET_DIR/payroll-$TIMESTAMP.sql.gz"
    log "开始备份 MySQL：$MYSQL_DATABASE@$MYSQL_HOST:$MYSQL_PORT"
    MYSQL_PWD="$PAYROLL_DB_PASSWORD" mysqldump \
        --host="$MYSQL_HOST" --port="$MYSQL_PORT" --user="$PAYROLL_DB_USER" \
        --single-transaction --quick --routines --events --triggers \
        --set-gtid-purged=OFF --no-tablespaces \
        --default-character-set=utf8mb4 \
        "$MYSQL_DATABASE" | gzip -9 >"$DUMP_FILE"
    log "MySQL 备份完成：$(basename "$DUMP_FILE")"
fi

# ---------------------------------------------------------------- Nacos
if [ "${SKIP_NACOS:-0}" = "1" ]; then
    log "SKIP_NACOS=1，跳过 Nacos 导出"
else
    NACOS_SERVER=${NACOS_SERVER:-http://127.0.0.1:8848}
    NACOS_SERVER=${NACOS_SERVER%/}
    case "$NACOS_SERVER" in
    */nacos) NACOS_BASE="$NACOS_SERVER" ;;
    *) NACOS_BASE="$NACOS_SERVER/nacos" ;;
    esac
    NACOS_GROUP=${NACOS_GROUP:-payroll-agent}
    NACOS_NAMESPACE=${NACOS_NAMESPACE:-}
    NACOS_ZIP="$TARGET_DIR/nacos-$NACOS_GROUP.zip"
    NACOS_TOKEN=""

    nacos_login() {
        # 凭据走 stdin，避免出现在 ps/argv 与 shell 历史里。
        local body
        body=$(printf 'username=%s&password=%s' "$NACOS_USERNAME" "$NACOS_PASSWORD" |
            curl -fsS -X POST "$NACOS_BASE/v1/auth/login" \
                -H 'Content-Type: application/x-www-form-urlencoded' \
                --data-binary @- 2>/dev/null || true)
        printf '%s' "$body" |
            sed -n 's/.*"accessToken"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
    }

    nacos_export() {
        local out=$1
        local url="$NACOS_BASE/v1/cs/configs?export=true&tenant=&group=$NACOS_GROUP&dataId=&appName=&ids="
        if [ -n "$NACOS_NAMESPACE" ]; then
            url="${url/tenant=/tenant=$NACOS_NAMESPACE}"
        fi
        if [ -n "$NACOS_TOKEN" ]; then
            url="$url&accessToken=$NACOS_TOKEN"
        fi
        curl -fsS -o "$out" "$url" 2>/dev/null
    }

    if [ -n "${NACOS_USERNAME:-}" ]; then
        [ -n "${NACOS_PASSWORD:-}" ] || fail "缺少 NACOS_PASSWORD"
        NACOS_TOKEN=$(nacos_login)
        [ -n "$NACOS_TOKEN" ] || fail "Nacos 登录失败（请检查 NACOS_USERNAME/NACOS_PASSWORD 与鉴权状态）"
    fi

    if nacos_export "$NACOS_ZIP"; then
        if [ -s "$NACOS_ZIP" ] && command -v unzip >/dev/null 2>&1; then
            manifest="$TARGET_DIR/nacos-$NACOS_GROUP.manifest.txt"
            if unzip -Z1 "$NACOS_ZIP" >"$manifest" 2>/dev/null; then
                count=$(wc -l <"$manifest" | tr -d ' ')
                log "Nacos 配置已导出：$count 个条目（namespace=${NACOS_NAMESPACE:-public}，group=$NACOS_GROUP）"
            fi
        elif [ -s "$NACOS_ZIP" ]; then
            log "Nacos 配置已导出：$(basename "$NACOS_ZIP")"
        else
            rm -f "$NACOS_ZIP"
            log "Nacos 导出为空（group=$NACOS_GROUP 下无配置）"
        fi
    else
        rm -f "$NACOS_ZIP"
        log "Nacos 不可达或导出失败，跳过配置备份（恢复前请线下确认配置来源）"
    fi
fi

# ---------------------------------------------------------------- 清单与保留策略
(
    cd "$TARGET_DIR"
    find . -type f ! -name SHA256SUMS | sort | xargs -r sha256sum >SHA256SUMS
)
log "备份完成：$TARGET_DIR"
log "恢复步骤见 docs/restore-runbook.md"

if [ "$RETENTION_DAYS" -gt 0 ] 2>/dev/null; then
    removed=$(find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d \
        -name 'payroll-backup-*' -mtime +"$RETENTION_DAYS" -print -exec rm -rf {} + | wc -l | tr -d ' ')
    if [ "$removed" -gt 0 ]; then
        log "已清理 $removed 个超过 $RETENTION_DAYS 天的旧备份"
    fi
fi
