-- 路由透明化与写操作确认：消息路由快照列 + 提案表。
-- H2 由 PayrollDatabase 内联 DDL 同步创建同构结构。

ALTER TABLE chat_messages ADD COLUMN routing_json LONGTEXT;

CREATE TABLE chat_action_proposals (
    proposal_id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL DEFAULT 0,
    agent_path VARCHAR(32),
    tool_name VARCHAR(128) NOT NULL,
    params_json LONGTEXT,
    summary VARCHAR(500) NOT NULL,
    risk_level VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    safety_source VARCHAR(32),
    status VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(128),
    snapshot_hash VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL,
    confirmed_by BIGINT,
    confirmed_at TIMESTAMP NULL,
    executed_at TIMESTAMP NULL,
    result_json LONGTEXT,
    error_message VARCHAR(500)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE UNIQUE INDEX uq_chat_action_proposals_idempotency
    ON chat_action_proposals (user_id, idempotency_key);
CREATE INDEX idx_chat_action_proposals_session
    ON chat_action_proposals (session_id, status);
