-- 模型用量事实：每次模型调用一行，看板按时间/模型/使用面/智能体/用户聚合。
-- H2 由 PayrollDatabase 内联 DDL 同步创建同构结构。

CREATE TABLE llm_usage (
    usage_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_id VARCHAR(64),
    session_id VARCHAR(64),
    agent_id VARCHAR(128),
    agent_name VARCHAR(128),
    role VARCHAR(64),
    stage_key VARCHAR(64),
    run_id BIGINT,
    model VARCHAR(128),
    provider VARCHAR(32),
    plane VARCHAR(32),
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    cached_tokens INT NOT NULL DEFAULT 0,
    total_tokens INT NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    success BOOLEAN NOT NULL DEFAULT TRUE,
    error_type VARCHAR(64),
    reply_id VARCHAR(64)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_llm_usage_time ON llm_usage (occurred_at);
CREATE INDEX idx_llm_usage_model ON llm_usage (model, occurred_at);
CREATE INDEX idx_llm_usage_user ON llm_usage (user_id, occurred_at);
CREATE INDEX idx_llm_usage_session ON llm_usage (session_id, occurred_at);
