-- 多节点轮次：每轮对话一条记录，事件本体在 Redis Stream。
-- H2 由 PayrollDatabase 内联 DDL 同步创建同构结构。

CREATE TABLE chat_turns (
    turn_id VARCHAR(36) PRIMARY KEY,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    user_message_id BIGINT NOT NULL DEFAULT 0,
    assistant_message_id BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    owner_node VARCHAR(64),
    fencing_token BIGINT NOT NULL DEFAULT 0,
    last_seq BIGINT NOT NULL DEFAULT 0,
    error_message VARCHAR(500),
    started_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_chat_turns_session ON chat_turns (session_id, started_at);
CREATE INDEX idx_chat_turns_status ON chat_turns (status, updated_at);
