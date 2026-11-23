-- 用户级工作偏好（对外命名「我的工作偏好」）：条目主表 + 抽取凭证表。
-- 两张表均按 user_id 隔离；H2 由 PayrollDatabase 内联 DDL 同步创建同构结构。

CREATE TABLE user_profile_items (
    item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    category VARCHAR(24) NOT NULL,
    item_key VARCHAR(64) NOT NULL,
    item_value VARCHAR(120) NOT NULL,
    confidence DOUBLE NOT NULL DEFAULT 0,
    source VARCHAR(16) NOT NULL,
    source_turn_id VARCHAR(36),
    evidence VARCHAR(200),
    status VARCHAR(16) NOT NULL,
    injected_count INT NOT NULL DEFAULT 0,
    last_injected_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE UNIQUE INDEX uq_profile_user_key
    ON user_profile_items (user_id, category, item_key);
CREATE INDEX idx_profile_user_status
    ON user_profile_items (user_id, status, updated_at);

CREATE TABLE user_profile_extractions (
    extraction_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    target_turn_id VARCHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    detail VARCHAR(200),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE UNIQUE INDEX uq_profile_extraction
    ON user_profile_extractions (user_id, target_turn_id);
CREATE INDEX idx_profile_extraction_status
    ON user_profile_extractions (status, updated_at);

-- 现有索引只有 (session_id, started_at) 与 (status, updated_at)，按用户取抽取窗口需要用户维度。
CREATE INDEX idx_chat_turns_user
    ON chat_turns (user_id, status, started_at);
