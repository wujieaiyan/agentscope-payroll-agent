-- P0-1：执行端心跳与失联判定解耦；历史轮次以 updated_at 回填。
ALTER TABLE chat_turns ADD COLUMN heartbeat_at TIMESTAMP NULL;
UPDATE chat_turns SET heartbeat_at = updated_at WHERE heartbeat_at IS NULL;
CREATE INDEX idx_chat_turns_heartbeat ON chat_turns (status, heartbeat_at);

-- P0-2：终态消息按 turn_id 幂等，重复提交与接管重放只保留一条助手消息。
ALTER TABLE chat_messages ADD COLUMN turn_id VARCHAR(36) NULL;
CREATE UNIQUE INDEX uk_chat_messages_turn ON chat_messages (turn_id);
