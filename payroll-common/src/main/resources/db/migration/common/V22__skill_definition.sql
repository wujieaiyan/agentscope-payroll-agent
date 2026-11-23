-- 技能存储落库（架构加固 M5.2）：数据库为权威，Nacos 退化为可选发布通道。
-- 与 V21 agent_definition 同构：revision 行级递增，写前重读比对（409）。
-- skill_id 列宽与 SkillIds 的 ID 规则（^[a-z][a-z0-9-]{1,63}$，上限 64）同源：
-- 超长的 Nacos dataId 本就过不了校验，列宽放宽只会让库里有读不到的行。
-- V22 在基线之上，是唯一会被 Flyway 真正执行的脚本：上一进程建到一半被杀时对象已在而迁移记录
-- 缺失，下次启动必须原样重放，所以建表带 IF NOT EXISTS。索引不各写一条 CREATE INDEX——MySQL 8 的
-- CREATE INDEX 不支持该子句（V1 头注已记，H2 放行而真库报错的语法只能靠约定挡住），
-- 折进建表体后索引随表一起幂等（V10 同构）。
CREATE TABLE IF NOT EXISTS skill_definition (
    skill_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NOT NULL DEFAULT '',
    tags VARCHAR(512) NOT NULL DEFAULT '',
    skill_version VARCHAR(32) NOT NULL DEFAULT '',
    builtin BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT NOT NULL DEFAULT 0,
    execution_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    content_hash CHAR(64) NOT NULL,
    content_length INT NOT NULL DEFAULT 0,
    resource_count INT NOT NULL DEFAULT 0,
    resource_bytes BIGINT NOT NULL DEFAULT 0,
    resource_hash CHAR(64) NOT NULL DEFAULT '',
    current_version_id BIGINT,
    revision BIGINT NOT NULL,
    created_by VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(128),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_skill_def_updated (updated_at, skill_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 正文按版本追加（D3a）：回滚只移动 skill_definition.current_version_id，不改写历史行。
CREATE TABLE IF NOT EXISTS skill_version (
    version_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    skill_id VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    body_md LONGTEXT NOT NULL,
    meta_json TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    content_length INT NOT NULL DEFAULT 0,
    change_note VARCHAR(200) NOT NULL DEFAULT '',
    created_by VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_skill_version_no (skill_id, version_no),
    KEY idx_skill_version_hash (content_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 文件清单挂在版本上，取代现网靠 mdHash 字符串对齐（结构上不可能错配）。
CREATE TABLE IF NOT EXISTS skill_resource (
    resource_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id BIGINT NOT NULL,
    skill_id VARCHAR(64) NOT NULL,
    path VARCHAR(200) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    media_type VARCHAR(64) NOT NULL DEFAULT 'text/plain',
    is_binary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_skill_resource_path (version_id, path),
    KEY idx_skill_resource_skill (skill_id, path),
    KEY idx_skill_resource_blob (sha256)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- blob 目录只存元数据，字节在对象存储/本地盘（D2a、D6a）；主键即内容指纹，跨版本去重。
CREATE TABLE IF NOT EXISTS skill_blob (
    sha256 CHAR(64) PRIMARY KEY,
    storage_key VARCHAR(512) NOT NULL,
    size_bytes BIGINT NOT NULL,
    media_type VARCHAR(64) NOT NULL DEFAULT 'text/plain',
    storage_location VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
