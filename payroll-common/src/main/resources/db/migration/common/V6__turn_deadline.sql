-- P1-2：轮次绝对截止时间。创建时固定，续流不再按每次连接重置；
-- 执行节点整体失联时由周期扫描按 deadline_at 收尾。
ALTER TABLE chat_turns ADD COLUMN deadline_at TIMESTAMP NULL;
-- TIMESTAMPADD 在 MySQL 与 H2(MySQL 模式) 下均可用，避免方言分支。
UPDATE chat_turns
SET deadline_at = TIMESTAMPADD(MINUTE, 30, started_at)
WHERE deadline_at IS NULL AND finished_at IS NULL;
CREATE INDEX idx_chat_turns_deadline ON chat_turns (status, deadline_at);
