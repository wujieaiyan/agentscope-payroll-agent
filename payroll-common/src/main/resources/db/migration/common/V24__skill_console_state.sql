-- 技能控制台读模型：数据工作区状态缓存与模板创建血缘。
-- 仅新增表，不改既有表；索引内联在 CREATE TABLE 内，MySQL 不支持 CREATE INDEX IF NOT EXISTS。
-- 状态值沿用 SandboxGateway.SkillData：UNKNOWN/INITIALIZING/READY/FAILED/BUSY/UNAVAILABLE/INVALID；
-- NOT_REQUIRED 不落库，由技能是否声明 setup 派生，避免声明变更后与缓存不同步。
-- 不存配额：沙箱状态没有配额字段，界面展示的「声明上限」取自 frontmatter metadata.data.max-bytes。

CREATE TABLE IF NOT EXISTS skill_data_state (
    skill_id     VARCHAR(64) PRIMARY KEY,
    status       VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    size_bytes   BIGINT NOT NULL DEFAULT 0,
    exit_code    INT,
    started_at   BIGINT NOT NULL DEFAULT 0,
    finished_at  BIGINT NOT NULL DEFAULT 0,
    message      VARCHAR(512) NOT NULL DEFAULT '',
    observed_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_skill_data_status (status, observed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS skill_creation_origin (
    skill_id     VARCHAR(64) PRIMARY KEY,
    created_from VARCHAR(64) NOT NULL,
    created_by   VARCHAR(128),
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_creation_origin_template (created_from, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
