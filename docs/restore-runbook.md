# 备份与恢复演练手册

对应 `docs/architecture-hardening-prd.md` 批次 3 O4。备份由 `scripts/backup.sh` 产出，本手册覆盖校验、MySQL / H2 / Nacos 三类恢复、冒烟查询与定期演练。

## 1. 备份产物

每次执行生成一个独立目录，不与历史备份混放：

```
$BACKUP_DIR/payroll-backup-YYYYMMDD-HHMMSS/
├── payroll-YYYYMMDD-HHMMSS.sql.gz   # MySQL 逻辑备份（H2 模式下为 payroll.mv.db 副本）
├── nacos-<group>.zip                # Nacos 该分组全部配置（v1 导出接口）
├── nacos-<group>.manifest.txt       # zip 内条目清单（需要 unzip）
└── SHA256SUMS                       # 上述文件的校验和
```

- 保留策略：默认删除超过 `RETENTION_DAYS`（7）天的 `payroll-backup-*` 目录。
- 密码只经 `MYSQL_PWD` 与 curl 标准输入传递，不写入产物、命令行与日志。
- Nacos 不可达时脚本只记录跳过，不算失败；恢复前需人工确认那次备份不含配置。

## 2. 执行备份

```bash
export PAYROLL_DB_URL='jdbc:mysql://127.0.0.1:3306/payroll?...'
export PAYROLL_DB_USER=payroll
export PAYROLL_DB_PASSWORD="$(cat /etc/payroll/db.pass)"   # 不要写进命令行
export NACOS_SERVER=http://127.0.0.1:8848
export NACOS_GROUP=payroll-agent
# 开启鉴权时再提供：
# export NACOS_USERNAME=...  NACOS_PASSWORD=...
BACKUP_DIR=/data/backups ./scripts/backup.sh
```

cron 示例（每天 02:30，凭据放在 600 权限的 EnvironmentFile 中，避免进入 shell 历史）：

```cron
30 2 * * * . /etc/payroll/backup.env && BACKUP_DIR=/data/backups /opt/payroll/scripts/backup.sh >>/var/log/payroll-backup.log 2>&1
```

H2 部署改为 `H2_FILE=/opt/payroll/data/payroll.mv.db`，并保证备份时 Web 已停机或处于低写入窗口（H2 单写者，热拷贝可能不一致，演练时以停机拷贝为准）。

## 3. 恢复前检查

```bash
cd /data/backups/payroll-backup-YYYYMMDD-HHMMSS
sha256sum -c SHA256SUMS
```

任一条目校验失败即停止，换一份备份。恢复窗口内：

1. 停 Web 与 agent-runtime（`systemctl stop payroll-web payroll-agent-runtime`），避免恢复期间仍有写入。
2. Redis 可保留不重启；会话锁与租约在应用恢复后会重建。
3. 先备份当前库/当前配置到另一个目录，再执行恢复，保证可回退。

## 4. MySQL 恢复

务必先恢复到临时库演练，确认数据完整后再切换生产库：

```bash
# ① 临时库演练
MYSQL_PWD="$PAYROLL_DB_PASSWORD" mysql -h 127.0.0.1 -u payroll -e \
  'CREATE DATABASE payroll_restore CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci'

gunzip -c payroll-YYYYMMDD-HHMMSS.sql.gz | \
  MYSQL_PWD="$PAYROLL_DB_PASSWORD" mysql -h 127.0.0.1 -u payroll \
    --default-character-set=utf8mb4 payroll_restore
```

```sql
-- ② 演练库校验（见 §7 冒烟查询）
```

生产恢复：

```bash
MYSQL_PWD="$PAYROLL_DB_PASSWORD" mysql -h 127.0.0.1 -u payroll \
  -e 'DROP DATABASE payroll; CREATE DATABASE payroll CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci'
gunzip -c payroll-YYYYMMDD-HHMMSS.sql.gz | \
  MYSQL_PWD="$PAYROLL_DB_PASSWORD" mysql -h 127.0.0.1 -u payroll \
    --default-character-set=utf8mb4 payroll
```

启动应用后由 Flyway 校验版本：备份来自旧版本时，启动会按序补齐缺失迁移；`flyway_schema_history` 校验和不一致时应用会拒绝启动，此时应换回与代码版本匹配的备份，而不是手工改历史表。

## 5. H2 恢复

1. 停 Web（H2 文件单写者，运行中覆盖会导致损坏）。
2. 把现有库移动到旁边留档：`mv data/payroll.mv.db data/payroll.mv.db.bak-$(date +%s)`。
3. 复制备份：`cp <备份目录>/payroll.mv.db data/`。
4. 启动 Web，按 §7 冒烟。

H2 备份只包含数据库；Nacos 配置与上传文件（`data/uploads` 等）需按 §6 与文件备份单独恢复。

## 6. Nacos 恢复

zip 内路径为 `<group>/<dataId>`，例如 `payroll-agent/payroll-agents.json`。

```bash
mkdir -p /tmp/nacos-restore && cd /tmp/nacos-restore
unzip -o /data/backups/payroll-backup-*/nacos-payroll-agent.zip

# 开启鉴权时先换 token（凭据走 stdin，避免出现在 argv）
TOKEN=$(printf 'username=%s&password=%s' "$NACOS_USERNAME" "$NACOS_PASSWORD" |
  curl -fsS -X POST "$NACOS_SERVER/nacos/v1/auth/login" \
    -H 'Content-Type: application/x-www-form-urlencoded' --data-binary @- |
  sed -n 's/.*"accessToken"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')

for f in payroll-agent/*; do
  dataId=$(basename "$f")
  case "$dataId" in
  *.json) type=json ;;
  *.md) type=markdown ;;
  *) type=text ;;
  esac
  curl -fsS -X POST "$NACOS_SERVER/nacos/v1/cs/configs" \
    --data-urlencode "dataId=$dataId" \
    --data-urlencode "group=payroll-agent" \
    --data-urlencode "type=$type" \
    --data-urlencode "content@$f" \
    ${TOKEN:+--data-urlencode "accessToken=$TOKEN"} >/dev/null
done
```

发布顺序：技能正文（`*.md`、`*.files.json`）与 `payroll-agents.json`、`payroll-flows.json` 先行，`payroll-runtime-settings.json` 最后，避免激活指向尚不存在的流程或智能体。旧版备份可能只有 `payroll-agent-settings.json` / `payroll-flow-settings.json`（O1 迁移前的两个文档），新版应用会读取并迁移，直接导入即可。

应用侧对列表有 TTL 缓存，恢复后重启 Web 与 runtime，或等待缓存过期再验收。

## 7. 冒烟查询

```sql
SELECT COUNT(*) FROM app_users;
SELECT COUNT(*) FROM chat_sessions;
SELECT COUNT(*) FROM chat_messages;
SELECT COUNT(*) FROM kb_document;
SELECT COUNT(*) FROM llm_usage;
SHOW COLUMNS FROM app_users LIKE 'must_change_password';
SELECT version, description, success FROM flyway_schema_history
 ORDER BY installed_rank DESC LIMIT 5;
```

接口与页面：

```bash
curl -fsS http://127.0.0.1:8080/health          # status=UP，components 无地址字段
# 用管理台账号登录，确认 200 且返回 must_change_password=false
curl -fsS -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<密码>"}'
```

再打开管理台：员工/用户列表有数据、会话列表可打开、知识库文档数量与恢复前一致、管理台保存一次配置返回 200（验证 Nacos 恢复后写路径正常）。

## 8. 演练节奏

- 每季度在临时库完整恢复一次（§3–§4 + §7），记录耗时与失败项。
- 每次升级数据库结构（新增 Flyway 迁移）后补做一次恢复演练，确认旧备份可在新代码上启动。
- 演练结束后清理 `payroll_restore` 库与 `/tmp/nacos-restore`，生产备份目录不受影响。
