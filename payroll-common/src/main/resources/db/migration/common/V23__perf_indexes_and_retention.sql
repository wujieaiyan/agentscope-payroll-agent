-- 性能索引、保留任务租约与上下文水位（C3/C4）。
-- 说明：应用启动时由 Flyway 执行；生产先用同一份 classpath 迁移在库上跑完 V23，
-- 再滚动发布应用，避免建索引阻塞 readiness。DDL 非事务，失败后必须整份清理再重跑。

CREATE INDEX idx_chat_turns_started ON chat_turns (started_at);
CREATE INDEX idx_chat_messages_created ON chat_messages (created_at, message_id);
CREATE INDEX idx_chat_sessions_created ON chat_sessions (created_at, session_id);
CREATE INDEX idx_chat_attachments_created ON chat_attachments (created_at, attachment_id);
CREATE INDEX idx_chat_tool_traces_created ON chat_tool_traces (created_at, trace_id);
CREATE INDEX idx_chat_feedback_created ON chat_feedback (created_at, feedback_id);
CREATE INDEX idx_chat_action_proposals_message ON chat_action_proposals (message_id);
CREATE INDEX idx_chat_action_proposals_created ON chat_action_proposals (created_at, proposal_id);
CREATE INDEX idx_llm_usage_plane_time ON llm_usage (plane, occurred_at);
CREATE INDEX idx_llm_usage_agent_time ON llm_usage (agent_id, occurred_at);
CREATE INDEX idx_llm_usage_time_id ON llm_usage (occurred_at, usage_id);
CREATE INDEX idx_agent_team_runs_created ON agent_team_runs (created_at);
CREATE INDEX idx_kb_chunk_series_seq ON kb_chunk (series_id, status, document_id, seq);
CREATE INDEX idx_kb_document_series_updated
    ON kb_document (series_id, deleted_at, updated_at, document_id);
CREATE INDEX idx_kb_document_series_dir_updated
    ON kb_document (series_id, deleted_at, directory_id, updated_at, document_id);
CREATE INDEX idx_kb_task_log_created ON kb_task_log (created_at, log_id);
CREATE INDEX idx_kb_task_status_created ON kb_ingest_task (task_status, created_at, task_id);
CREATE INDEX idx_audit_result_time ON audit_log (result, occurred_at);
CREATE INDEX idx_profile_extractions_updated ON user_profile_extractions (updated_at);

CREATE TABLE job_lease (
    job_name    VARCHAR(64)  NOT NULL,
    owner       VARCHAR(128) NOT NULL,
    acquired_at TIMESTAMP    NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    PRIMARY KEY (job_name)
);

ALTER TABLE chat_sessions ADD COLUMN context_through_message_id BIGINT;
