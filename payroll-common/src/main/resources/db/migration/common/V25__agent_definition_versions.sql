-- 智能体定义版本化（架构 S2）：与 skill_version 同构的追加式历史。
-- 每次写入都追加一条版本行；回滚 = 追加一条 payload 复用目标版本的新行再切定义行，
-- 历史行一字不改，序号单调递增（「回到 v2」在库里是新增 v3），因此 current 恒为最大 version_no。
-- 升级前已存在的智能体没有历史就回滚不了，所以把当前 payload 一次性补成 v1「升级基线」；
-- 迁移按 IF NOT EXISTS / NOT EXISTS 幂等，进程中途被杀后原样重放不会补出第二行 v1。
CREATE TABLE IF NOT EXISTS agent_version (
    version_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id VARCHAR(128) NOT NULL,
    version_no INT NOT NULL,
    payload_json LONGTEXT NOT NULL,
    change_note VARCHAR(200) NOT NULL DEFAULT '',
    created_by VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_version_no (agent_id, version_no),
    KEY idx_agent_version_created (agent_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO agent_version (agent_id, version_no, payload_json, change_note, created_by, created_at)
SELECT d.id, 1, d.payload_json, '升级基线', d.updated_by, d.updated_at
FROM agent_definition d
WHERE NOT EXISTS (SELECT 1 FROM agent_version v WHERE v.agent_id = d.id);
